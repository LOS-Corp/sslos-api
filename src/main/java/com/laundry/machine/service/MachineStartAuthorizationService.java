package com.laundry.machine.service;

import com.laundry.booking.event.BookingReleased;
import com.laundry.booking.service.MachineSlotService;
import com.laundry.booking.service.MachineSlotService.MachineSlot;
import com.laundry.machine.dto.CheckInResponse;
import com.laundry.machine.domain.CheckInWindowPolicy;
import com.laundry.machine.entity.*;
import com.laundry.machine.exception.CheckInRejectedException;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.machine.exception.StaleAuthorizationException;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.time.ZoneId;
import java.util.UUID;

/**
 * QR check-in + physical START authorization service.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #checkIn(String, UUID)} picks the customer's machine slot (a booking allocation), creates
 *       a {@link MachineStartAuthorization} (status PENDING) and publishes
 *       an {@code AUTHORIZE_START} MQTT command.</li>
 *   <li>The device ACKs via {@code AUTH_ACK} →
 *       {@link #handleAuthAck(Machine, String, String, boolean, String, String, String)}
 *       flips the row to GRANTED and locks the {@code deviceBootId}.</li>
 *   <li>The customer presses the physical START button → device publishes
 *       {@code START_REQUEST} → {@link #handleStartRequest(Machine, UUID, String, String, String)}
 *       sets START_PENDING and synchronously publishes {@code START_APPROVAL}.</li>
 *   <li>Device confirms by publishing {@code STARTED} →
 *       {@link #handleStarted(Machine, UUID, String, UUID, String)} flips
 *       the row to CONSUMED.</li>
 * </ol>
 *
 * <h2>Failure paths</h2>
 * <ul>
 *   <li>Expiry: see {@link com.laundry.machine.scheduler.StartAuthorizationExpiryScheduler}.</li>
 *   <li>Device rejected the START: {@link #handleStartRejected}.</li>
 *   <li>Booking cancelled: {@link #onBookingReleased(BookingReleased)}.</li>
 * </ul>
 */
@Service
@Slf4j
public class MachineStartAuthorizationService {

    private final MachineRepository machineRepository;
    private final MachineSlotService machineSlots;
    private final PaymentRepository paymentRepository;
    private final MachineStartAuthorizationRepository authorizationRepository;
    private final PlatformTransactionManager transactionManager;
    private final com.laundry.booking.service.BookingLifecycleService bookingLifecycleService;
    private final CheckInWindowPolicy checkInWindowPolicy;
    private final StartAuthorizationPublisher publisher;

    public MachineStartAuthorizationService(
            MachineRepository machineRepository,
            MachineSlotService machineSlots,
            PaymentRepository paymentRepository,
            MachineStartAuthorizationRepository authorizationRepository,
            PlatformTransactionManager transactionManager,
            com.laundry.booking.service.BookingLifecycleService bookingLifecycleService,
            CheckInWindowPolicy checkInWindowPolicy,
            StartAuthorizationPublisher publisher) {
        this.machineRepository = machineRepository;
        this.machineSlots = machineSlots;
        this.paymentRepository = paymentRepository;
        this.authorizationRepository = authorizationRepository;
        this.transactionManager = transactionManager;
        this.bookingLifecycleService = bookingLifecycleService;
        this.checkInWindowPolicy = checkInWindowPolicy;
        this.publisher = publisher;
    }

    @Value("${app.machine.start-authorization.ttl-seconds:120}")
    private int authorizationTtlSeconds;

    /** START pressed → STARTED normally takes ~1 s; the device gives up after 5 s. */
    private static final int START_PENDING_MAX_SECONDS = 15;

    /**
     * Pre-configured REQUIRES_NEW template — used by {@link #revokeAndCommitInNewTx}
     * to persist a terminal state (REVOKED/EXPIRED) even though the caller is
     * about to throw {@code StaleAuthorizationException}, which would otherwise
     * roll back the outer {@code @Transactional} boundary.
     *
     * <p>Initialized in {@link #initRevokeInNewTxTemplate()} after construction
     * so we can apply the propagation setting without needing to construct
     * the template eagerly in the {@code @RequiredArgsConstructor}-generated
     * field list (which would require it to be final and pre-built).
     */
    private TransactionTemplate revokeInNewTxTemplate;

    @jakarta.annotation.PostConstruct
    void initRevokeInNewTxTemplate() {
        this.revokeInNewTxTemplate = new TransactionTemplate(transactionManager);
        this.revokeInNewTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ─── Public API ─────────────────────────────────────────────────────────

    /**
     * Entry point for {@code POST /api/machines/check-in}.
     *
     * @param machineCode code from the QR URL (e.g. "WM001")
     * @param userId      authenticated principal — taken from Spring Security
     * @return immutable response for the customer-facing polling endpoint
     * @throws CheckInRejectedException when any of the business rules fail
     */
    @Transactional
    public CheckInResponse checkIn(String machineCode, UUID userId) {
        if (machineCode == null || machineCode.isBlank()) {
            throw new CheckInRejectedException("machineCode is required");
        }
        if (userId == null) {
            // Defensive — the controller layer already enforces auth, but we
            // refuse to silently behave like an unauthenticated call.
            throw new CheckInRejectedException("Authentication required");
        }

        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> new MachineNotFoundException("Machine not found: " + machineCode));

        // 1. Machine is commissioned + online + available.
        if (!machine.isCommissioned()) {
            throw new CheckInRejectedException("Machine has not been commissioned yet");
        }
        if (!machine.isOnline()) {
            throw new CheckInRejectedException("Machine is currently offline");
        }
        if (machine.getStatus() == null
                || machine.getStatus() == MachineStatus.OUT_OF_SERVICE
                || machine.getStatus() == MachineStatus.ERROR
                || machine.getStatus() == MachineStatus.RUNNING) {
            throw new CheckInRejectedException(
                    "Machine is not available (status=" + machine.getStatus() + ")");
        }

        // 2. No conflicting ACTIVE authorization for this machine.
        Optional<MachineStartAuthorization> existing =
                authorizationRepository.findActiveByMachine(machine.getId());
        if (existing.isPresent()) {
            // Allow the same user to re-check-in if their existing row is theirs
            // and still useful — easier UX than bouncing them to /status.
            MachineStartAuthorization e = existing.get();
            if (!e.getUserId().equals(userId)
                    || e.getExpiresAt().isBefore(OffsetDateTime.now())) {
                throw new CheckInRejectedException(
                        "Another authorization is already active for this machine");
            }
            log.info("[{}] Reusing existing authorization {} for user {}",
                    machineCode, e.getId(), userId);
            return toCheckInResponse(e);
        }

        // 3. The customer's slot on this machine (an allocation of a live, paid booking).
        //    The messages below are matched by the customer apps; keep them stable.
        MachineSlot slot = eligibleSlot(machine, userId)
                .orElseThrow(() -> new CheckInRejectedException(
                        "No confirmed reservation found for this machine"));

        // 4. One booking pays for one cycle per machine.
        if (slot.used()) {
            throw new CheckInRejectedException(
                    "This reservation has already been used for a wash");
        }

        // 5. Payment must be PAID.
        Optional<Payment> paidPayment = paymentRepository.findByTransactionIdOrderByCreatedAtDesc(slot.transactionId())
                .stream()
                .filter(p -> p.getStatus() == PaymentStatus.PAID)
                .findFirst();
        if (paidPayment.isEmpty()) {
            throw new CheckInRejectedException("Payment for this reservation is not confirmed");
        }

        // 6. Check-in window (BR-06, BR-07): T−15 … max(T, PAID) + 10 minutes.
        //    Payment times are local wall-clock values of this JVM's zone.
        Instant paidAt = paidPayment.get().getPaidAt() == null ? null
                : paidPayment.get().getPaidAt().atZone(ZoneId.systemDefault()).toInstant();
        CheckInWindowPolicy.Window window = checkInWindowPolicy.windowFor(slot.from(), paidAt);
        switch (window.positionOf(Instant.now())) {
            case NOT_YET_OPEN -> throw new CheckInRejectedException("Reservation window has not opened yet");
            case CLOSED -> throw new CheckInRejectedException("Reservation window has already closed");
            case OPEN -> { }
        }

        // 7. Persist the authorization row + publish the MQTT command.
        OffsetDateTime created = OffsetDateTime.now();
        OffsetDateTime expires = created.plusSeconds(authorizationTtlSeconds);
        MachineStartAuthorization auth = MachineStartAuthorization.builder()
                .allocationId(slot.allocationId())
                .bookingId(slot.bookingId())
                .transactionId(slot.transactionId())
                .userId(userId)
                .machineId(machine.getId())
                .machineCode(machineCode)
                .status(MachineStartAuthorizationStatus.PENDING)
                .createdAt(created)
                .expiresAt(expires)
                .build();

        auth = authorizationRepository.save(auth);
        log.info("[{}] Authorization created: id={} user={} booking={} allocation={} expiresAt={}",
                machineCode, auth.getId(), userId, slot.bookingId(), slot.allocationId(), expires);

        // Publish only after commit: the device answers AUTH_ACK within ~100 ms,
        // and an ACK that beats the commit finds no row, so the authorization
        // stays PENDING and the later START_REQUEST is rejected (BAD_STATUS_PENDING).
        MachineStartAuthorization committed = auth;
        runAfterCommit(() -> publisher.authorizeStart(machine, committed));
        return toCheckInResponse(auth);
    }

    // ─── Admin test run (no booking, no QR) ────────────────────────────────

    /**
     * Unlock the physical START button for a test cycle from the admin console.
     * Same device protocol as {@link #checkIn}, minus the reservation and
     * payment checks; the row is flagged {@code testRun}. The machine only has
     * to be online with a provisioned device — it need not be commissioned,
     * so a freshly installed machine can be run before opening it for sale.
     */
    @Transactional
    public CheckInResponse adminTestStart(String machineCode, UUID adminId) {
        if (adminId == null) {
            throw new CheckInRejectedException("Authentication required");
        }
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> new MachineNotFoundException("Machine not found: " + machineCode));
        if (!machine.isOnline()) {
            throw new CheckInRejectedException("Machine is currently offline");
        }
        if (machine.getStatus() == MachineStatus.RUNNING || machine.getStatus() == MachineStatus.ERROR
                || machine.getStatus() == MachineStatus.OUT_OF_SERVICE) {
            throw new CheckInRejectedException(
                    "Machine is not available (status=" + machine.getStatus() + ")");
        }

        Optional<MachineStartAuthorization> existing = authorizationRepository.findActiveByMachine(machine.getId());
        if (existing.isPresent()) {
            MachineStartAuthorization e = existing.get();
            if (e.isTestRun() && e.getExpiresAt().isAfter(OffsetDateTime.now())) {
                return toCheckInResponse(e);
            }
            throw new CheckInRejectedException(e.isTestRun()
                    ? "A previous test authorization is still active; cancel it first"
                    : "A customer authorization is active on this machine");
        }

        OffsetDateTime created = OffsetDateTime.now();
        MachineStartAuthorization auth = authorizationRepository.save(MachineStartAuthorization.builder()
                .testRun(true)
                .userId(adminId)
                .machineId(machine.getId())
                .machineCode(machine.getMachineCode())
                .status(MachineStartAuthorizationStatus.PENDING)
                .createdAt(created)
                .expiresAt(created.plusSeconds(authorizationTtlSeconds))
                .build());
        log.info("[{}] Admin test authorization created: id={} admin={}", machineCode, auth.getId(), adminId);

        MachineStartAuthorization committed = auth;
        runAfterCommit(() -> publisher.authorizeStart(machine, committed));
        return toCheckInResponse(auth);
    }

    /** Latest test authorization on the machine (any status), for the admin console to poll. */
    @Transactional(readOnly = true)
    public Optional<CheckInResponse> latestTestStart(String machineCode) {
        return machineRepository.findByMachineCode(machineCode)
                .flatMap(m -> authorizationRepository.findFirstByMachineIdAndTestRunTrueOrderByCreatedAtDesc(m.getId()))
                .map(this::toCheckInResponse);
    }

    /**
     * Simulate pressing the physical START button during an admin test run.
     * The device runs the same path as a real press (interlocks, START_REQUEST,
     * START_APPROVAL), so the full flow is exercised. Only a GRANTED test-run
     * authorization qualifies; customer cycles still need a person at the machine.
     */
    @Transactional(readOnly = true)
    public void pressTestStart(String machineCode) {
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> new MachineNotFoundException("Machine not found: " + machineCode));
        if (!machine.isOnline()) {
            throw new CheckInRejectedException("Machine is currently offline");
        }
        MachineStartAuthorization auth = authorizationRepository.findActiveByMachine(machine.getId())
                .filter(MachineStartAuthorization::isTestRun)
                .filter(a -> a.getStatus() == MachineStartAuthorizationStatus.GRANTED)
                .filter(a -> a.getExpiresAt().isAfter(OffsetDateTime.now()))
                .orElseThrow(() -> new CheckInRejectedException(
                        "No granted test-run authorization on this machine; unlock START first"));

        if (!publisher.pressStart(machine, auth)) {
            throw new CheckInRejectedException("Could not send the START press to the machine");
        }
        log.info("[{}] Simulated START press sent for test auth={}", machineCode, auth.getId());
    }

    /** Cancel an active test authorization; the device locks START again. */
    @Transactional
    public void cancelTestStart(String machineCode) {
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> new MachineNotFoundException("Machine not found: " + machineCode));
        authorizationRepository.findActiveByMachineForUpdate(machine.getId())
                .filter(MachineStartAuthorization::isTestRun)
                .ifPresent(a -> revokeInternal(a, "ADMIN_CANCELLED", null, true));
    }

    // ─── Device-driven events ──────────────────────────────────────────────

    /**
     * Handle {@code AUTH_ACK} from the device. Flips PENDING → GRANTED and
     * captures the boot session ID.
     *
     * <p>Idempotent: if the row is already GRANTED with the same boot ID,
     * we no-op. If the row is in a terminal state we ACK back the
     * appropriate REVOKE_START so the device unlocks the button.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void handleAuthAck(Machine machine,
                              UUID authorizationId,
                              String commandId,
                              boolean accepted,
                              String result,
                              String reason,
                              String bootId) {
        MachineStartAuthorization auth = lockOrThrow(authorizationId, machine.getId());

        // ACL-style sanity: commandId must match.
        if (commandId != null && auth.getStatus() == MachineStartAuthorizationStatus.PENDING) {
            log.debug("[{}] AUTH_ACK commandId={} matched authorization {}", machine.getMachineCode(), commandId, auth.getId());
        }

        if (!accepted) {
            revokeInternal(auth, "DEVICE_REJECTED", reason, true);
            return;
        }

        if (auth.getStatus() == MachineStartAuthorizationStatus.GRANTED
                && java.util.Objects.equals(auth.getDeviceBootId(), bootId)) {
            // ACK replay — nothing to do.
            log.debug("[{}] AUTH_ACK replay for {} — ignored", machine.getMachineCode(), auth.getId());
            return;
        }

        if (!auth.isActive()) {
            // PENDING/GRANTED/START_PENDING only — terminal rows: re-arm the device with REVOKE.
            log.info("[{}] AUTH_ACK for terminal authorization {} (status={}) — sending REVOKE",
                    machine.getMachineCode(), auth.getId(), auth.getStatus());
            publisher.revokeStart(machine, auth, "TERMINAL_AUTH_ACK");
            return;
        }

        auth.setStatus(MachineStartAuthorizationStatus.GRANTED);
        auth.setGrantedAt(OffsetDateTime.now());
        auth.setDeviceBootId(bootId);
        authorizationRepository.save(auth);
        log.info("[{}] Authorization GRANTED: {} bootId={}", machine.getMachineCode(), auth.getId(), bootId);
    }

    /**
     * Handle {@code START_REQUEST} from the device. The customer just pressed
     * the physical START button — the backend validates everything one last
     * time, flips the row to START_PENDING, and synchronously publishes
     * {@code START_APPROVAL}.
     *
     * @return the freshly-minted {@code runId} so the caller can echo it back
     *         to the firmware.
     * @throws StaleAuthorizationException if any of the safety checks fail.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public UUID handleStartRequest(Machine machine,
                                   UUID authorizationId,
                                   String startRequestId,
                                   String bootId) {
        MachineStartAuthorization auth = lockOrThrow(authorizationId, machine.getId());

        // The device can only know this authorizationId from AUTHORIZE_START,
        // so a START_REQUEST for a PENDING row is an implicit AUTH_ACK (the
        // explicit ACK was lost or failed to process).
        if (auth.getStatus() == MachineStartAuthorizationStatus.PENDING
                && bootId != null && !bootId.isBlank()) {
            log.info("[{}] START_REQUEST on PENDING authorization {} — treating as implicit AUTH_ACK",
                    machine.getMachineCode(), auth.getId());
            auth.setStatus(MachineStartAuthorizationStatus.GRANTED);
            auth.setGrantedAt(OffsetDateTime.now());
            auth.setDeviceBootId(bootId);
        }

        if (auth.getStatus() != MachineStartAuthorizationStatus.GRANTED) {
            publisher.startApproval(machine, auth, startRequestId, null,
                    "BAD_STATUS_" + auth.getStatus(), bootId);
            // A START_REQUEST for a non-GRANTED row must terminate the
            // authorization — otherwise the firmware sees a non-APPROVED
            // verdict but the backend keeps an ACTIVE row that could be
            // replayed. We don't revoke here if the row is already
            // terminal (idempotency guard).
            if (!auth.isTerminal()) {
                // REQUIRES_NEW so the revoke survives the upcoming throw.
                revokeAndCommitInNewTx(auth.getId(), "BAD_STATUS_AT_START",
                        "START_REQUEST while status=" + auth.getStatus());
            }
            throw new StaleAuthorizationException(authorizationId,
                    "Authorization not in GRANTED state (was " + auth.getStatus() + ")");
        }
        if (bootId != null && auth.getDeviceBootId() != null
                && !bootId.equals(auth.getDeviceBootId())) {
            publisher.startApproval(machine, auth, startRequestId, null,
                    "BOOT_ID_MISMATCH", bootId);
            // ESP32 reboot → invalidate the row so the next scan creates a fresh auth.
            revokeAndCommitInNewTx(auth.getId(), "BOOT_ID_MISMATCH",
                    "expected=" + auth.getDeviceBootId() + " got=" + bootId);
            throw new StaleAuthorizationException(authorizationId,
                    "bootId mismatch: backend=" + auth.getDeviceBootId() + " device=" + bootId);
        }
        if (auth.getExpiresAt().isBefore(OffsetDateTime.now())) {
            // Compensate: revoke locally so the next check-in is clean.
            revokeAndCommitInNewTx(auth.getId(), "EXPIRED_AT_START",
                    "Authorization expired before START");
            publisher.startApproval(machine, auth, startRequestId, null,
                    "EXPIRED", bootId);
            throw new StaleAuthorizationException(authorizationId,
                    "Authorization expired before START was processed");
        }
        if (machine.getStatus() == MachineStatus.RUNNING) {
            publisher.startApproval(machine, auth, startRequestId, null,
                    "MACHINE_ALREADY_RUNNING", bootId);
            throw new StaleAuthorizationException(authorizationId,
                    "Machine already running");
        }

        auth.setStatus(MachineStartAuthorizationStatus.START_PENDING);
        // The device drops an approval after 5 s; if STARTED never comes, let the sweep
        // close the row soon instead of leaving it START_PENDING for the whole TTL.
        OffsetDateTime startDeadline = OffsetDateTime.now().plusSeconds(START_PENDING_MAX_SECONDS);
        if (auth.getExpiresAt().isAfter(startDeadline)) auth.setExpiresAt(startDeadline);
        UUID runId = UUID.randomUUID();
        auth.setRunId(runId);
        authorizationRepository.save(auth);
        log.info("[{}] Authorization START_PENDING: {} runId={}", machine.getMachineCode(), auth.getId(), runId);

        // Publish approval after commit so the device's STARTED reply sees
        // START_PENDING (otherwise handleStarted ignores it and the row never
        // reaches CONSUMED).
        runAfterCommit(() -> publisher.startApproval(machine, auth, startRequestId, runId, "APPROVED", bootId));
        return runId;
    }

    /**
     * Handle {@code STARTED} from the device → CONSUMED.
     */
    @Transactional
    public void handleStarted(Machine machine,
                              UUID authorizationId,
                              String startRequestId,
                              UUID runId,
                              String bootId) {
        MachineStartAuthorization auth = lockOrThrow(authorizationId, machine.getId());

        if (auth.getStatus() == MachineStartAuthorizationStatus.CONSUMED) {
            // Idempotent replay.
            log.debug("[{}] STARTED replay for {} — already CONSUMED", machine.getMachineCode(), auth.getId());
            return;
        }
        if (auth.getStatus() != MachineStartAuthorizationStatus.START_PENDING) {
            log.warn("[{}] STARTED for authorization {} but status={} — ignoring",
                    machine.getMachineCode(), auth.getId(), auth.getStatus());
            return;
        }
        if (auth.getRunId() != null && runId != null && !auth.getRunId().equals(runId)) {
            log.warn("[{}] STARTED runId mismatch for authorization {}: auth.runId={} device.runId={}",
                    machine.getMachineCode(), auth.getId(), auth.getRunId(), runId);
            return;
        }

        auth.setStatus(MachineStartAuthorizationStatus.CONSUMED);
        auth.setConsumedAt(OffsetDateTime.now());
        authorizationRepository.save(auth);
        log.info("[{}] Authorization CONSUMED: {} runId={}", machine.getMachineCode(), auth.getId(), runId);

        if (auth.getAllocationId() == null) return;  // admin test run: no booking to advance
        // The booking is now in use; the machine's COMPLETED (or the sweep) closes it out.
        if (machineSlots.markRunning(auth.getAllocationId())) {
            bookingLifecycleService.onCycleStarted(auth.getAllocationId());
        }
    }

    /**
     * Handle {@code START_REJECTED} from the device — terminal failure.
     */
    @Transactional
    public void handleStartRejected(Machine machine,
                                    UUID authorizationId,
                                    String startRequestId,
                                    String reason,
                                    String bootId) {
        Optional<MachineStartAuthorization> opt =
                authorizationRepository.findActiveByMachineForUpdate(machine.getId());
        opt.filter(a -> a.getId().equals(authorizationId)).ifPresent(a ->
                revokeInternal(a, "DEVICE_START_REJECTED", reason, true));
    }

    /**
     * Handle {@code START_FAILED} from the device.
     */
    @Transactional
    public void handleStartFailed(Machine machine,
                                  UUID authorizationId,
                                  String startRequestId,
                                  String reason,
                                  String bootId) {
        Optional<MachineStartAuthorization> opt =
                authorizationRepository.findActiveByMachineForUpdate(machine.getId());
        opt.filter(a -> a.getId().equals(authorizationId)).ifPresent(a ->
                revokeInternal(a, "DEVICE_START_FAILED", reason, true));
    }

    /**
     * Handle {@code AUTH_EXPIRED} from the device — typically fired when the
     * local timer on the firmware hit zero before the customer pressed START.
     */
    @Transactional
    public void handleAuthExpired(Machine machine, UUID authorizationId, String bootId) {
        Optional<MachineStartAuthorization> opt =
                authorizationRepository.findActiveByMachineForUpdate(machine.getId());
        opt.filter(a -> a.getId().equals(authorizationId)).ifPresent(a ->
                revokeInternal(a, "DEVICE_REPORTED_EXPIRED", "auth expired on device", true));
    }

    /** A cancelled booking must not keep an unlocked START button (UC-08). Runs after the cancellation commits. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingReleased(BookingReleased event) {
        revokeInNewTxTemplate.executeWithoutResult(status -> revokeByBooking(event.bookingId(), event.reason()));
    }

    /** Revokes every active authorization of a booking and tells the device to lock START again. */
    @Transactional
    public int revokeByBooking(UUID bookingId, String reason) {
        List<MachineStartAuthorization> active = authorizationRepository.findActiveByBooking(bookingId);
        active.forEach(auth -> revokeInternal(auth, reason, null, true));
        if (!active.isEmpty()) {
            log.info("Revoked {} active authorization(s) for booking {} reason={}", active.size(), bookingId, reason);
        }
        return active.size();
    }

    /**
     * Bulk-revoke ACTIVE rows whose {@code expiresAt} has passed. Used by
     * {@link com.laundry.machine.scheduler.StartAuthorizationExpiryScheduler}.
     */
    @Transactional
    public int sweepExpired(OffsetDateTime cutoff) {
        List<MachineStartAuthorization> stale = authorizationRepository.findExpired(cutoff);
        if (stale.isEmpty()) {
            return 0;
        }
        List<UUID> ids = stale.stream().map(MachineStartAuthorization::getId).toList();
        int n = authorizationRepository.markExpired(ids, OffsetDateTime.now());

        // Publish REVOKE_START so the device unlocks the button immediately,
        // even if its local timer lags.
        for (MachineStartAuthorization a : stale) {
            machineRepository.findById(a.getMachineId()).ifPresent(m ->
                    publisher.revokeStart(m, a, "AUTO_EXPIRED"));
        }
        log.info("Sweep expired: {} row(s) flipped to EXPIRED", n);
        return n;
    }

    // ─── Look-ups ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Optional<MachineStartAuthorization> findOwnedByUser(UUID authId, UUID userId) {
        return authorizationRepository.findByIdAndUserId(authId, userId);
    }

    // ─── Internals ─────────────────────────────────────────────────────────

    /**
     * The slot to start: old slots that were never closed out must not shadow the booking just made, so
     * unused slots whose window has not ended come first (earliest start). The fallbacks only let
     * {@link #checkIn} explain why nothing is eligible.
     */
    private Optional<MachineSlot> eligibleSlot(Machine machine, UUID userId) {
        Instant now = Instant.now();
        List<MachineSlot> owned = machineSlots.customerSlots(machine.getId(), userId);
        List<MachineSlot> unused = owned.stream().filter(slot -> !slot.used()).toList();
        return unused.stream()
                .filter(slot -> !slot.until().isBefore(now))
                .min(java.util.Comparator.comparing(MachineSlot::from))
                .or(() -> unused.stream().findFirst())
                .or(() -> owned.stream().findFirst());
    }

    private MachineStartAuthorization lockOrThrow(UUID authorizationId, UUID machineId) {
        MachineStartAuthorization auth = authorizationRepository
                .findById(authorizationId)
                .orElseThrow(() -> new StaleAuthorizationException(authorizationId,
                        "Unknown authorizationId"));
        if (!auth.getMachineId().equals(machineId)) {
            throw new StaleAuthorizationException(authorizationId,
                    "Authorization does not belong to this machine");
        }
        return auth;
    }

    private void revokeInternal(MachineStartAuthorization auth,
                                String reason,
                                String detail,
                                boolean publishDeviceRevoke) {
        if (auth.isTerminal()) {
            return;
        }
        auth.setStatus(MachineStartAuthorizationStatus.REVOKED);
        auth.setRevokedAt(OffsetDateTime.now());
        auth.setRevokeReason(reason + (detail != null ? ": " + detail : ""));
        authorizationRepository.save(auth);
        if (publishDeviceRevoke) {
            machineRepository.findById(auth.getMachineId()).ifPresent(m ->
                    publisher.revokeStart(m, auth, reason));
        }
        log.info("Authorization {} → REVOKED (reason={})", auth.getId(), auth.getRevokeReason());
    }

    /**
     * Same as {@link #revokeInternal} but runs in its own transaction
     * ({@code REQUIRES_NEW}). Used when we want to PERSIST the revoke
     * decision even though the caller is about to throw
     * {@link StaleAuthorizationException} — otherwise Spring's default
     * rollback-on-runtime-exception would lose our work.
     *
     * <p>Implemented via {@link TransactionTemplate} rather than a
     * {@code @Transactional(REQUIRES_NEW)} self-invocation through the
     * CGLIB proxy — the latter silently degrades to running inside the
     * outer transaction because Spring AOP cannot intercept
     * {@code this.method(...)} calls, and the {@code appCtx.getBean(...)}
     * workaround proved unreliable in test contexts.
     */
    public void revokeAndCommitInNewTx(UUID authorizationId, String reason, String detail) {
        if (revokeInNewTxTemplate == null) {
            // Defensive: PostConstruct hasn't run yet (very early lifecycle).
            initRevokeInNewTxTemplate();
        }
        revokeInNewTxTemplate.executeWithoutResult(status -> {
            authorizationRepository.findById(authorizationId).ifPresent(a -> {
                if (!a.isTerminal()) {
                    a.setStatus(MachineStartAuthorizationStatus.REVOKED);
                    a.setRevokedAt(OffsetDateTime.now());
                    a.setRevokeReason(reason + (detail != null ? ": " + detail : ""));
                    authorizationRepository.save(a);
                    machineRepository.findById(a.getMachineId())
                            .ifPresent(m -> publisher.revokeStart(m, a, reason));
                    log.info("Authorization {} → REVOKED in new tx (reason={})", a.getId(), a.getRevokeReason());
                }
            });
        });
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    
    
    
    private CheckInResponse toCheckInResponse(MachineStartAuthorization a) {
        long remaining = Math.max(0,
                java.time.Duration.between(OffsetDateTime.now(), a.getExpiresAt()).getSeconds());
        return CheckInResponse.builder()
                .authorizationId(a.getId())
                .bookingId(a.getBookingId())
                .machineId(a.getMachineId())
                .machineCode(a.getMachineCode())
                .authorizationStatus(a.getStatus())
                .createdAt(a.getCreatedAt())
                .expiresAt(a.getExpiresAt())
                .remainingSeconds(remaining)
                .build();
    }
}
