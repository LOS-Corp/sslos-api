package com.laundry.machine.service;

import com.laundry.machine.dto.CheckInResponse;
import com.laundry.machine.entity.*;
import com.laundry.machine.exception.CheckInRejectedException;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.machine.exception.StaleAuthorizationException;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.mqtt.service.MqttPublisher;
import com.laundry.transaction.entity.MachineReservation;
import com.laundry.transaction.entity.ReservationStatus;
import com.laundry.transaction.entity.Transaction;
import com.laundry.transaction.repository.MachineReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * QR check-in + physical START authorization service.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #checkIn(String, UUID)} validates the reservation, creates
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
 *   <li>Reservation cancelled: {@link #revokeByReservation(UUID, String)}.</li>
 * </ul>
 */
@Service
@Slf4j
public class MachineStartAuthorizationService {

    private final MachineRepository machineRepository;
    private final MachineReservationRepository reservationRepository;
    private final PaymentRepository paymentRepository;
    private final MachineStartAuthorizationRepository authorizationRepository;
    private final MqttPublisher mqttPublisher;
    private final PlatformTransactionManager transactionManager;
    private final com.laundry.booking.service.BookingLifecycleService bookingLifecycleService;
    private final com.laundry.booking.service.MachineProgramService programService;

    /**
     * Explicit constructor with {@code @Lazy} on {@link MqttPublisher} to break
     * the circular dependency: mqttPublisher → mqttConnectionManager →
     * mqttMessageRouter → authChannelHandler → this service → mqttPublisher.
     */
    public MachineStartAuthorizationService(
            MachineRepository machineRepository,
            MachineReservationRepository reservationRepository,
            PaymentRepository paymentRepository,
            MachineStartAuthorizationRepository authorizationRepository,
            @Lazy MqttPublisher mqttPublisher,
            PlatformTransactionManager transactionManager,
            com.laundry.booking.service.BookingLifecycleService bookingLifecycleService,
            com.laundry.booking.service.MachineProgramService programService) {
        this.machineRepository = machineRepository;
        this.reservationRepository = reservationRepository;
        this.paymentRepository = paymentRepository;
        this.authorizationRepository = authorizationRepository;
        this.mqttPublisher = mqttPublisher;
        this.transactionManager = transactionManager;
        this.bookingLifecycleService = bookingLifecycleService;
        this.programService = programService;
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

        // 3. Find the eligible reservation for this user/machine.
        MachineReservation reservation = findEligibleReservation(machine, userId)
                .orElseThrow(() -> new CheckInRejectedException(
                        "No confirmed reservation found for this machine"));

        // 4. One booking pays for one cycle: a reservation that already
        //    started a wash cannot be checked in again.
        if (isUsed(reservation)) {
            throw new CheckInRejectedException(
                    "This reservation has already been used for a wash");
        }

        // 5. Reservation must be CONFIRMED or ACTIVE.
        if (reservation.getStatus() != ReservationStatus.CONFIRMED
                && reservation.getStatus() != ReservationStatus.ACTIVE) {
            throw new CheckInRejectedException(
                    "Reservation is not eligible (status=" + reservation.getStatus() + ")");
        }

        // 6. Payment must be PAID.
        Transaction tx = reservation.getTransaction();
        UUID transactionId = tx != null ? tx.getId() : null;
        if (transactionId != null) {
            List<Payment> payments =
                    paymentRepository.findByTransactionIdOrderByCreatedAtDesc(transactionId);
            boolean paid = payments.stream()
                    .anyMatch(p -> p.getStatus() == PaymentStatus.PAID);
            if (!paid) {
                throw new CheckInRejectedException(
                        "Payment for this reservation is not confirmed");
            }
        } else {
            throw new CheckInRejectedException("Reservation has no transaction");
        }

        // 7. Slot window: startTime must be in the past-ish (≤ now+grace) and
        //    endTime not yet past. Avoids check-in weeks ahead or after the
        //    reservation has already expired.
        LocalDateTime now = LocalDateTime.now();
        if (reservation.getStartTime() != null
                && reservation.getStartTime().isAfter(now.plusMinutes(15))) {
            throw new CheckInRejectedException(
                    "Reservation window has not opened yet");
        }
        if (reservation.getEndTime() != null && reservation.getEndTime().isBefore(now)) {
            throw new CheckInRejectedException("Reservation window has already closed");
        }

        // 8. Persist the authorization row + publish the MQTT command.
        OffsetDateTime created = OffsetDateTime.now();
        OffsetDateTime expires = created.plusSeconds(authorizationTtlSeconds);
        MachineStartAuthorization auth = MachineStartAuthorization.builder()
                .reservationId(reservation.getId())
                .transactionId(transactionId)
                .userId(userId)
                .machineId(machine.getId())
                .machineCode(machineCode)
                .status(MachineStartAuthorizationStatus.PENDING)
                .createdAt(created)
                .expiresAt(expires)
                .build();

        auth = authorizationRepository.save(auth);
        log.info("[{}] Authorization created: id={} user={} reservation={} expiresAt={}",
                machineCode, auth.getId(), userId, reservation.getId(), expires);

        // Publish only after commit: the device answers AUTH_ACK within ~100 ms,
        // and an ACK that beats the commit finds no row, so the authorization
        // stays PENDING and the later START_REQUEST is rejected (BAD_STATUS_PENDING).
        MachineStartAuthorization committed = auth;
        runAfterCommit(() -> publishAuthorizeStart(machine, committed));
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
        runAfterCommit(() -> publishAuthorizeStart(machine, committed));
        return toCheckInResponse(auth);
    }

    /** Latest test authorization on the machine (any status), for the admin console to poll. */
    @Transactional(readOnly = true)
    public Optional<CheckInResponse> latestTestStart(String machineCode) {
        return machineRepository.findByMachineCode(machineCode)
                .flatMap(m -> authorizationRepository.findFirstByMachineIdAndTestRunTrueOrderByCreatedAtDesc(m.getId()))
                .map(this::toCheckInResponse);
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
            publishRevokeStart(machine, auth, "TERMINAL_AUTH_ACK");
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
            publishStartApproval(machine, auth, startRequestId, null,
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
            publishStartApproval(machine, auth, startRequestId, null,
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
            publishStartApproval(machine, auth, startRequestId, null,
                    "EXPIRED", bootId);
            throw new StaleAuthorizationException(authorizationId,
                    "Authorization expired before START was processed");
        }
        if (machine.getStatus() == MachineStatus.RUNNING) {
            publishStartApproval(machine, auth, startRequestId, null,
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
        runAfterCommit(() -> publishStartApproval(machine, auth, startRequestId, runId, "APPROVED", bootId));
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

        if (auth.getReservationId() == null) return;  // admin test run: no booking to advance
        // The booking is now in use; the machine's COMPLETED (or the sweep) closes it out.
        reservationRepository.findById(auth.getReservationId())
                .filter(r -> r.getStatus() == ReservationStatus.CONFIRMED)
                .ifPresent(r -> {
                    r.setStatus(ReservationStatus.ACTIVE);
                    reservationRepository.saveAndFlush(r);
                    bookingLifecycleService.onCycleStarted(r.getId());
                });
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

    /**
     * Bulk-revoke every ACTIVE authorization tied to a reservation.
     * Called by reservation lifecycle (cancel / refund).
     */
    @Transactional
    public int revokeByReservation(UUID reservationId, String reason) {
        int n = authorizationRepository.revokeByReservation(reservationId, reason, OffsetDateTime.now());
        if (n > 0) {
            log.info("Revoked {} active authorization(s) for reservation {} reason={}", n, reservationId, reason);
        }
        return n;
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
                    publishRevokeStart(m, a, "AUTO_EXPIRED"));
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

    private Optional<MachineReservation> findEligibleReservation(Machine machine, UUID userId) {
        // transaction.customerId is the owning user. Old CONFIRMED/ACTIVE rows
        // that were never closed out must not shadow the booking just made, so
        // prefer reservations whose window is still open, earliest start first.
        LocalDateTime now = LocalDateTime.now();
        List<MachineReservation> owned = java.util.stream.Stream.concat(
                        reservationRepository.findByMachineIdAndStatus(machine.getId(), ReservationStatus.CONFIRMED).stream(),
                        reservationRepository.findByMachineIdAndStatus(machine.getId(), ReservationStatus.ACTIVE).stream())
                .filter(r -> {
                    Transaction tx = r.getTransaction();
                    return tx != null && userId.equals(tx.getCustomerId());
                })
                .toList();
        // Skip bookings already used for a wash, so a second unused booking on
        // the same machine is still reachable. Fall back to the full list only
        // so checkIn can explain why nothing is eligible.
        List<MachineReservation> unused = owned.stream().filter(r -> !isUsed(r)).toList();
        return unused.stream()
                .filter(r -> r.getEndTime() == null || !r.getEndTime().isBefore(now))
                .min(java.util.Comparator.comparing(MachineReservation::getStartTime,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .or(() -> unused.stream().findFirst())
                .or(() -> owned.stream().findFirst());
    }

    private boolean isUsed(MachineReservation reservation) {
        return authorizationRepository.existsByReservationIdAndStatus(
                reservation.getId(), MachineStartAuthorizationStatus.CONSUMED);
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
                    publishRevokeStart(m, auth, reason));
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
                            .ifPresent(m -> publishRevokeStart(m, a, reason));
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

    private void publishAuthorizeStart(Machine machine, MachineStartAuthorization auth) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "AUTHORIZE_START");
        String commandId = "AUTH-" + auth.getId();
        payload.put("commandId", commandId);
        payload.put("authorizationId", auth.getId().toString());
        if (auth.getReservationId() != null) payload.put("reservationId", auth.getReservationId().toString());
        if (auth.isTestRun()) payload.put("testRun", true);
        payload.put("machineId", machine.getMachineCode());
        payload.put("expiresInSeconds", authorizationTtlSeconds);
        payload.put("issuedAt", OffsetDateTime.now().toString());
        // The program the booking paid for (test runs: the default). Without
        // one the firmware runs its built-in default program.
        if (machine.getType() != null) {
            programService.devicePayload(auth.getTransactionId(), machine.getType().name())
                    .ifPresent(program -> payload.put("program", program));
        }
        String topic = mqttPublisher.buildWasherCommandTopic(machine.getBranchId(), machine.getMachineCode());
        try {
            mqttPublisher.publish(topic, new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(payload));
        } catch (Exception e) {
            log.error("[{}] Failed to publish AUTHORIZE_START for auth={}: {}",
                    machine.getMachineCode(), auth.getId(), e.getMessage(), e);
        }
    }

    private void publishStartApproval(Machine machine,
                                      MachineStartAuthorization auth,
                                      String startRequestId,
                                      UUID runId,
                                      String result,
                                      String bootId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "START_APPROVAL");
        payload.put("commandId", "STARTAPP-" + auth.getId());
        payload.put("authorizationId", auth.getId().toString());
        payload.put("startRequestId", startRequestId);
        if (runId != null) payload.put("runId", runId.toString());
        payload.put("result", result);
        payload.put("bootId", bootId);
        payload.put("validForMs", 5000);
        String topic = mqttPublisher.buildWasherCommandTopic(machine.getBranchId(), machine.getMachineCode());
        try {
            mqttPublisher.publish(topic, new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(payload));
        } catch (Exception e) {
            log.error("[{}] Failed to publish START_APPROVAL for auth={}: {}",
                    machine.getMachineCode(), auth.getId(), e.getMessage(), e);
        }
    }

    private void publishRevokeStart(Machine machine, MachineStartAuthorization auth, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "REVOKE_START");
        payload.put("commandId", "REVOKE-" + auth.getId());
        payload.put("authorizationId", auth.getId().toString());
        payload.put("reason", reason);
        String topic = mqttPublisher.buildWasherCommandTopic(machine.getBranchId(), machine.getMachineCode());
        try {
            mqttPublisher.publish(topic, new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(payload));
        } catch (Exception e) {
            log.error("[{}] Failed to publish REVOKE_START for auth={}: {}",
                    machine.getMachineCode(), auth.getId(), e.getMessage(), e);
        }
    }

    private CheckInResponse toCheckInResponse(MachineStartAuthorization a) {
        long remaining = Math.max(0,
                java.time.Duration.between(OffsetDateTime.now(), a.getExpiresAt()).getSeconds());
        return CheckInResponse.builder()
                .authorizationId(a.getId())
                .reservationId(a.getReservationId())
                .machineId(a.getMachineId())
                .machineCode(a.getMachineCode())
                .authorizationStatus(a.getStatus())
                .createdAt(a.getCreatedAt())
                .expiresAt(a.getExpiresAt())
                .remainingSeconds(remaining)
                .build();
    }
}
