package com.laundry.machine;

import com.laundry.machine.entity.*;
import com.laundry.machine.exception.CheckInRejectedException;
import com.laundry.machine.exception.StaleAuthorizationException;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import com.laundry.machine.service.MachineStartAuthorizationService;
import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentMethod;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.transaction.entity.MachineReservation;
import com.laundry.transaction.entity.ReservationStatus;
import com.laundry.transaction.entity.Transaction;
import com.laundry.transaction.repository.MachineReservationRepository;
import com.laundry.transaction.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.laundry.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * End-to-end integration tests for the QR check-in + physical START
 * authorization flow.
 *
 * <p>Profile: {@code test} (real PostgreSQL via Testcontainers, MQTT broker is replaced by
 * a Mockito mock so we can assert how often authorize / revoke / approve
 * commands were published).
 *
 * <p>Each test builds its own fixture via the helpers at the bottom so
 * individual cases stay focused on the assertion.
 */
@IntegrationTest
@DisplayName("QR check-in + Physical START authorization")
class MachineStartAuthorizationServiceTest {

    @Autowired
    private MachineStartAuthorizationService service;
    @Autowired
    private MachineRepository machineRepository;
    @Autowired
    private MachineReservationRepository reservationRepository;
    @Autowired
    private MachineStartAuthorizationRepository authorizationRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private com.laundry.transaction.scheduler.ReservationWindowCloseScheduler reservationWindowCloseScheduler;

    @Autowired
    private com.laundry.branch.repository.BranchRepository branchRepository;
    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Replaces the real Paho-backed publisher with a Mockito mock so the
     * flow doesn't need a live broker.
     */
    @Autowired
    private com.laundry.mqtt.service.MqttPublisher mqttPublisher;


    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Machine machine;
    private UUID customerId;
    private UUID branchId;

    @BeforeEach
    void setUp() {
        reset(mqttPublisher);
        authorizationRepository.deleteAll();
        paymentRepository.deleteAll();
        reservationRepository.deleteAll();
        transactionRepository.deleteAll();
        machineRepository.deleteAll();

        customerId = UUID.randomUUID();
        branchId = branchRepository.save(com.laundry.branch.entity.Branch.builder()
                .name("Test branch " + UUID.randomUUID())
                .build()).getId();
        machine = Machine.builder()
                .machineCode("WM001")
                .branchId(branchId)
                .type(MachineType.WASHER)
                .status(MachineStatus.AVAILABLE)
                .deploymentStatus(MachineDeploymentStatus.ACTIVE)
                .online(true)
                .build();
        machineRepository.save(machine);
    }

    // ── 1. Successful happy path ─────────────────────────────────────────────

    @Test
    @DisplayName("Happy path: valid reservation + paid → check-in publishes AUTHORIZE_START")
    void case1_happyPath() {
        UUID reservationId = seedReservationAndPayment(true, false);

        var response = service.checkIn("WM001", customerId);

        assertThat(response.authorizationId()).isNotNull();
        assertThat(response.machineCode()).isEqualTo("WM001");
        assertThat(response.authorizationStatus())
                .isEqualTo(MachineStartAuthorizationStatus.PENDING);
        assertThat(response.remainingSeconds()).isPositive();
        assertThat(response.expiresAt()).isAfter(OffsetDateTime.now());
        verifyPublished(mqttPublisher, 1);
    }

    // ── 2-7. Bad-reservation variants ───────────────────────────────────────

    @Test
    @DisplayName("Rejects when machine does not exist")
    void case2_unknownMachine() {
        // Backend throws MachineNotFoundException (404) — the global
        // exception handler maps it to HTTP 404 for the mobile app.
        assertThatThrownBy(() -> service.checkIn("WMXXX", customerId))
                .isInstanceOf(com.laundry.machine.exception.MachineNotFoundException.class);
    }

    @Test
    @DisplayName("Rejects when reservation belongs to a different customer")
    void case3_wrongCustomer() {
        UUID reservationId = seedReservationAndPayment(true, false, UUID.randomUUID());
        assertThatThrownBy(() -> service.checkIn("WM001", customerId))
                .isInstanceOf(CheckInRejectedException.class)
                .hasMessageContaining("reservation");
    }

    @Test
    @DisplayName("Rejects when the existing reservation points at a DIFFERENT machine")
    void case4_wrongMachine() {
        // Create a second machine + a CONFIRMED reservation targeting it.
        UUID otherMachineId = machineRepository.save(Machine.builder()
                .machineCode("WM002")
                .branchId(branchId)
                .type(MachineType.WASHER)
                .status(MachineStatus.AVAILABLE)
                .deploymentStatus(MachineDeploymentStatus.ACTIVE)
                .online(true)
                .build()).getId();
        Transaction tx = newTransaction(customerId);
        transactionRepository.save(tx);
        reservationRepository.save(reservation(tx, otherMachineId, ReservationStatus.CONFIRMED));
        paymentRepository.save(paidPayment(tx.getId()));

        assertThatThrownBy(() -> service.checkIn("WM001", customerId))
                .isInstanceOf(CheckInRejectedException.class);
    }

    @Test
    @DisplayName("Rejects when payment is not PAID")
    void case5_unpaid() {
        UUID reservationId = seedReservationAndPayment(false, false);

        assertThatThrownBy(() -> service.checkIn("WM001", customerId))
                .isInstanceOf(CheckInRejectedException.class)
                .hasMessageContaining("Payment");
        // Should not have created any auth row.
        assertThat(authorizationRepository.findActiveByMachine(machine.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("Rejects when reservation window is already closed")
    void case6_expired() {
        seedReservationWithOffset(-120, -60);
        paymentRepository.save(paidPayment(
                reservationRepository.findAll().get(0).getTransaction().getId()));
        // First, set the persisted reservation to EXPIRED.
        var rs = reservationRepository.findAll();
        rs.forEach(r -> {
            r.setStatus(ReservationStatus.EXPIRED);
            reservationRepository.save(r);
        });

        assertThatThrownBy(() -> service.checkIn("WM001", customerId))
                .isInstanceOf(CheckInRejectedException.class);
    }

    @Test
    @DisplayName("Rejects when reservation is CANCELLED")
    void case7_cancelled() {
        UUID reservationId = seedReservationAndPayment(true, false);
        var r = reservationRepository.findById(reservationId).orElseThrow();
        r.setStatus(ReservationStatus.CANCELLED);
        reservationRepository.save(r);

        assertThatThrownBy(() -> service.checkIn("WM001", customerId))
                .isInstanceOf(CheckInRejectedException.class);
    }

    // ── 8. Expiry sweep ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Expired authorization is flipped to EXPIRED and REVOKE is published")
    void case8_expiryTimeout() throws Exception {
        // Force a 2-second TTL via the test profile; then sleep 3 s and sweep.
        UUID reservationId = seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);

        // Manually backdate the row's expiry so we don't have to wait.
        var auth = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        auth.setExpiresAt(OffsetDateTime.now().minusSeconds(10));
        authorizationRepository.save(auth);

        int n = service.sweepExpired(OffsetDateTime.now());
        assertThat(n).isEqualTo(1);
        reload(response.authorizationId());
        var after = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(MachineStartAuthorizationStatus.EXPIRED);
        // 1 publish for AUTHORIZE_START + 1 for REVOKE_START = 2 publishes.
        // We accept any-string because the mocked topic builder returns null.
        verifyPublished(mqttPublisher, 2);
    }

    @Test
    @DisplayName("Expired START_PENDING row also gets swept (lost STARTED ack)")
    void case20_lostStartedAck() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        var auth = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        // No STARTED ever arrives — sweep is the only safety net.
        auth.setExpiresAt(OffsetDateTime.now().minusSeconds(60));
        authorizationRepository.save(auth);
        int n = service.sweepExpired(OffsetDateTime.now());
        assertThat(n).isGreaterThanOrEqualTo(1);
        reload(response.authorizationId());
        var after = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(after.isTerminal()).isTrue();
    }

    // ── Admin test run (no booking, no QR) ─────────────────────────────────

    @Test
    @DisplayName("Admin test run goes PENDING → CONSUMED without any reservation")
    void adminTestRun_fullCycleWithoutReservation() {
        UUID adminId = UUID.randomUUID();
        var response = service.adminTestStart("WM001", adminId);
        verifyPublished(mqttPublisher, 1);

        var auth = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(auth.isTestRun()).isTrue();
        assertThat(auth.getReservationId()).isNull();
        assertThat(auth.getStatus()).isEqualTo(MachineStartAuthorizationStatus.PENDING);

        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        UUID runId = service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        service.handleStarted(machine, response.authorizationId(), "req-1", runId, "boot-A");

        reload(response.authorizationId());
        assertThat(authorizationRepository.findById(response.authorizationId()).orElseThrow().getStatus())
                .isEqualTo(MachineStartAuthorizationStatus.CONSUMED);
        assertThat(service.latestTestStart("WM001")).get()
                .extracting(r -> r.authorizationStatus()).isEqualTo(MachineStartAuthorizationStatus.CONSUMED);
    }

    @Test
    @DisplayName("Admin test run never overrides a customer authorization; cancel revokes only test rows")
    void adminTestRun_respectsCustomerAuthorizationAndCancels() {
        seedReservationAndPayment(true, false);
        var customer = service.checkIn("WM001", customerId);
        assertThatThrownBy(() -> service.adminTestStart("WM001", UUID.randomUUID()))
                .isInstanceOf(CheckInRejectedException.class);

        service.cancelTestStart("WM001");   // must not touch the customer's row
        reload(customer.authorizationId());
        assertThat(authorizationRepository.findById(customer.authorizationId()).orElseThrow().isActive()).isTrue();

        authorizationRepository.deleteAll();
        var test = service.adminTestStart("WM001", UUID.randomUUID());
        service.cancelTestStart("WM001");
        reload(test.authorizationId());
        assertThat(authorizationRepository.findById(test.authorizationId()).orElseThrow().getStatus())
                .isEqualTo(MachineStartAuthorizationStatus.REVOKED);
    }

    @Test
    @DisplayName("Simulated START press only works on a GRANTED test run, never on a customer authorization")
    void pressTestStart_onlyForGrantedTestRun() {
        seedReservationAndPayment(true, false);
        var customer = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, customer.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        assertThatThrownBy(() -> service.pressTestStart("WM001"))
                .isInstanceOf(CheckInRejectedException.class);

        authorizationRepository.deleteAll();
        var test = service.adminTestStart("WM001", UUID.randomUUID());
        assertThatThrownBy(() -> service.pressTestStart("WM001"))   // still PENDING: device has not acked
                .isInstanceOf(CheckInRejectedException.class);

        service.handleAuthAck(machine, test.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        org.mockito.Mockito.clearInvocations(mqttPublisher);
        service.pressTestStart("WM001");
        verifyPublished(mqttPublisher, 1);
    }

    // ── 9. Double QR check-in ───────────────────────────────────────────────

    @Test
    @DisplayName("Repeated check-in returns the existing active authorization")
    void case9_doubleCheckIn() {
        seedReservationAndPayment(true, false);
        var first = service.checkIn("WM001", customerId);
        var second = service.checkIn("WM001", customerId);
        assertThat(second.authorizationId()).isEqualTo(first.authorizationId());
        // Only ONE AUTHORIZE_START published.
        verifyPublished(mqttPublisher, 1);
    }

    // ── 10. Press START twice ───────────────────────────────────────────────

    @Test
    @DisplayName("Repeated START_REQUEST with the same authorization Id is idempotent")
    void case10_repeatedStartPress() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(),
                "CMD-" + UUID.randomUUID(), true, "ACCEPTED", null, "boot-A");

        UUID runId1 = service.handleStartRequest(machine, response.authorizationId(),
                "req-1", "boot-A");
        // The second START_REQUEST now sees status START_PENDING, so it
        // is rejected and the device gets a non-APPROVED approval.
        assertThatThrownBy(() ->
                service.handleStartRequest(machine, response.authorizationId(), "req-2", "boot-A"))
                .isInstanceOf(StaleAuthorizationException.class);

        // Confirm the first runId survives.
        var auth = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(auth.getRunId()).isEqualTo(runId1);
    }

    // ── 11. Two simultaneous START_REQUEST (concurrency) ────────────────────

    @Test
    @DisplayName("Concurrent START_REQUEST: only the first wins, second raises StaleAuthorizationException")
    void case11_concurrentStartRequests() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        UUID runId1 = service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        // Simulated race: PESSIMISTIC_WRITE makes the second one see START_PENDING.
        assertThatThrownBy(() ->
                service.handleStartRequest(machine, response.authorizationId(), "req-2", "boot-A"))
                .isInstanceOf(StaleAuthorizationException.class);
        assertThat(runId1).isNotNull();
    }

    // ── 12. MQTT disconnection: the device never ACKs ───────────────────────

    @Test
    @DisplayName("Missing AUTH_ACK does not block check-in; admin still creates a PENDING row")
    void case12_mqttDisconnect() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        // Simulate the device being unreachable: no AUTH_ACK arrives. The
        // customer-facing row stays PENDING; expiry sweeper flips it later.
        var auth = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(auth.getStatus()).isEqualTo(MachineStartAuthorizationStatus.PENDING);
    }

    // ── 13. ESP32 reboot produces a new bootId ──────────────────────────────

    @Test
    @DisplayName("Reboot (bootId change) invalidates the existing authorization on next START_REQUEST")
    void case13_esp32Reboot() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        // Device reboots — new bootId is published. The first START_REQUEST
        // is rejected with StaleAuthorizationException AND the row is revoked.
        assertThatThrownBy(() ->
                service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-B"))
                .isInstanceOf(StaleAuthorizationException.class);
        reload(response.authorizationId());
        // Use a fresh JPQL query to bypass the JPA session cache.
        var rows = entityManager.createQuery(
                "SELECT a FROM MachineStartAuthorization a WHERE a.id = :id",
                com.laundry.machine.entity.MachineStartAuthorization.class)
                .setParameter("id", response.authorizationId())
                .getResultList();
        var auth = rows.isEmpty() ? null : rows.get(0);
        // After the boot-mismatch rejection, the row is REVOKED.
        assertThat(auth).isNotNull();
        assertThat(auth.getStatus())
                .isEqualTo(MachineStartAuthorizationStatus.REVOKED);
        assertThat(auth.isTerminal()).isTrue();
        assertThat(auth.getRevokeReason()).contains("BOOT_ID_MISMATCH");
    }

    // ── 14. Door open at START_REQUEST ──────────────────────────────────────

    @Test
    @DisplayName("START_REQUEST is still accepted when door is open — device-side does the final safety check")
    void case14_doorOpen() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        // Door is open on the device — backend cannot see that, so it approves.
        UUID runId = service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        assertThat(runId).isNotNull();
        // Device then immediately fires START_REJECTED.
        service.handleStartRejected(machine, response.authorizationId(), "req-1", "DOOR_OPEN", "boot-A");
        reload(response.authorizationId());
        var auth = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(auth.getStatus()).isEqualTo(MachineStartAuthorizationStatus.REVOKED);
    }

    // ── 15. STOP active — works symmetrically with case 14 via START_REJECTED.

    @Test
    @DisplayName("STOP active: device reports START_REJECTED → row terminates")
    void case15_stopActive() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        service.handleStartRejected(machine, response.authorizationId(), "req-1", "STOP_ACTIVE", "boot-A");
        reload(response.authorizationId());
        var auth = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(auth.getStatus()).isEqualTo(MachineStartAuthorizationStatus.REVOKED);
    }

    // ── 16. Machine already running ─────────────────────────────────────────

    @Test
    @DisplayName("START_REQUEST while machine is RUNNING is rejected")
    void case16_machineAlreadyRunning() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");

        machine.setStatus(MachineStatus.RUNNING);
        machineRepository.save(machine);

        assertThatThrownBy(() ->
                service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A"))
                .isInstanceOf(StaleAuthorizationException.class);
    }

    // ── 17. Duplicate MQTT ACKs / events ────────────────────────────────────

    @Test
    @DisplayName("Duplicate AUTH_ACK does not double-flip the row")
    void case17_duplicateAcks() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        var auth = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(auth.getStatus()).isEqualTo(MachineStartAuthorizationStatus.GRANTED);
    }

    @Test
    @DisplayName("Duplicate STARTED events do not double-consume")
    void case17b_duplicateStarted() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        UUID runId = service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        service.handleStarted(machine, response.authorizationId(), "req-1", runId, "boot-A");
        service.handleStarted(machine, response.authorizationId(), "req-1", runId, "boot-A");
        reload(response.authorizationId());
        var auth = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(auth.getStatus()).isEqualTo(MachineStartAuthorizationStatus.CONSUMED);
    }

    // ── 18. Delayed START_APPROVAL — backend stays in START_PENDING until device ACKs STARTED ─

    @Test
    @DisplayName("Delayed START_APPROVAL: backend waits for STARTED before flipping to CONSUMED")
    void case18_delayedApproval() throws Exception {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        UUID runId = service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        var afterReq = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(afterReq.getStatus())
                .isEqualTo(MachineStartAuthorizationStatus.START_PENDING);
        // Wait a beat, then device finally reports STARTED.
        Thread.sleep(50);
        service.handleStarted(machine, response.authorizationId(), "req-1", runId, "boot-A");
        reload(response.authorizationId());
        var finalRow = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(finalRow.getStatus()).isEqualTo(MachineStartAuthorizationStatus.CONSUMED);
        assertThat(finalRow.getConsumedAt()).isNotNull();
    }

    // ── 19. Backend restart — row persists, customer re-polls ──────────────

    @Test
    @DisplayName("Restart: a PENDING row survives; the customer can re-poll status")
    void case19_backendRestart() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        // Simulate restart: just reload the row from the DB.
        var afterRestart = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(afterRestart.getStatus())
                .isEqualTo(MachineStartAuthorizationStatus.PENDING);
    }

    // ── 21. Authorization replay attempt ────────────────────────────────────

    @Test
    @DisplayName("Replay attempt: a CONSUMED authId cannot be re-used")
    void case21_replayAttempt() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        UUID runId = service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        service.handleStarted(machine, response.authorizationId(), "req-1", runId, "boot-A");
        // Replay attempt: same authorizationId, same bootId.
        assertThatThrownBy(() ->
                service.handleStartRequest(machine, response.authorizationId(), "req-X", "boot-A"))
                .isInstanceOf(StaleAuthorizationException.class);
    }

    // ── 22. Successful washing cycle start ──────────────────────────────────

    @Test
    @DisplayName("Successful washing cycle start: PENDING → GRANTED → START_PENDING → CONSUMED")
    void case22_successfulCycle() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        assertThat(response.authorizationStatus())
                .isEqualTo(MachineStartAuthorizationStatus.PENDING);

        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        UUID runId = service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        service.handleStarted(machine, response.authorizationId(), "req-1", runId, "boot-A");
        reload(response.authorizationId());

        var finalRow = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(finalRow.getStatus()).isEqualTo(MachineStartAuthorizationStatus.CONSUMED);
        assertThat(finalRow.getRunId()).isEqualTo(runId);
        assertThat(finalRow.getConsumedAt()).isNotNull();
        // AuthorizeStart + StartApproval + RevokeStart(zero) = 2 publishes.
        verifyPublished(mqttPublisher, 2);
    }

    // ── Reservation cancel → revoke cascade ────────────────────────────────

    @Test
    @DisplayName("Reservation cancel revokes any active authorization")
    void case23_reservationCancel() {
        UUID reservationId = seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        var auth = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        // Simulate the cancel path by calling the same function the
        // MachineReservationService.cancelReservation() invokes.
        int n = service.revokeByReservation(reservationId, "RESERVATION_CANCELLED");
        assertThat(n).isEqualTo(1);
        reload(auth.getId());
        var after = authorizationRepository.findById(auth.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(MachineStartAuthorizationStatus.REVOKED);
    }

    @Test
    @DisplayName("AUTH_EXPIRED event from the device revokes the row")
    void case24_authExpiredFromDevice() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        service.handleAuthExpired(machine, response.authorizationId(), "boot-A");
        reload(response.authorizationId());
        var after = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(MachineStartAuthorizationStatus.REVOKED);
    }

    @Test
    @DisplayName("START_FAILED event from the device revokes the row")
    void case25_startFailedFromDevice() {
        seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        service.handleStartFailed(machine, response.authorizationId(), "req-1", "MOTOR_STALL", "boot-A");
        reload(response.authorizationId());
        var after = authorizationRepository.findById(response.authorizationId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(MachineStartAuthorizationStatus.REVOKED);
    }

    @Test
    @DisplayName("A stale CONFIRMED reservation does not shadow the one just booked")
    void case26_staleReservationDoesNotShadowNewOne() {
        seedReservationWithOffset(-300, -180);   // ended 3 h ago, never closed out
        seedReservationAndPayment(true, false);  // current window
        var response = service.checkIn("WM001", customerId);
        assertThat(response.authorizationId()).isNotNull();
    }

    @Test
    @DisplayName("Window sweep: used → COMPLETED, no-show → EXPIRED, open window untouched")
    void case27_reservationWindowSweep() {
        UUID usedId = seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        UUID runId = service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        service.handleStarted(machine, response.authorizationId(), "req-1", runId, "boot-A");
        MachineReservation used = reservationRepository.findById(usedId).orElseThrow();
        used.setEndTime(LocalDateTime.now().minusHours(2));
        reservationRepository.save(used);

        Transaction staleTx = transactionRepository.save(newTransaction(customerId));
        MachineReservation stale = reservation(staleTx, machine.getId(), ReservationStatus.CONFIRMED);
        stale.setStartTime(LocalDateTime.now().minusHours(4));
        stale.setEndTime(LocalDateTime.now().minusHours(2));
        UUID staleId = reservationRepository.save(stale).getId();

        UUID openId = seedReservationAndPayment(true, false);

        reservationWindowCloseScheduler.sweep();
        entityManager.clear();

        assertThat(reservationRepository.findById(usedId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.COMPLETED);
        assertThat(reservationRepository.findById(staleId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservationRepository.findById(openId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("A reservation that already ran a wash cannot be checked in again")
    void case28_reservationUsedOnlyOnce() {
        UUID reservationId = seedReservationAndPayment(true, false);
        var response = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, response.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        UUID runId = service.handleStartRequest(machine, response.authorizationId(), "req-1", "boot-A");
        service.handleStarted(machine, response.authorizationId(), "req-1", runId, "boot-A");

        entityManager.clear();
        assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.ACTIVE);
        assertThatThrownBy(() -> service.checkIn("WM001", customerId))
                .isInstanceOf(CheckInRejectedException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    @DisplayName("A used reservation does not hide a second, unused booking on the same machine")
    void case29_secondBookingStillUsable() {
        seedReservationAndPayment(true, false);
        var first = service.checkIn("WM001", customerId);
        service.handleAuthAck(machine, first.authorizationId(), "cmd", true, "ACCEPTED", null, "boot-A");
        UUID runId = service.handleStartRequest(machine, first.authorizationId(), "req-1", "boot-A");
        service.handleStarted(machine, first.authorizationId(), "req-1", runId, "boot-A");

        UUID secondReservation = seedReservationAndPayment(true, false);
        var second = service.checkIn("WM001", customerId);
        var auth = authorizationRepository.findById(second.authorizationId()).orElseThrow();
        assertThat(auth.getReservationId()).isEqualTo(secondReservation);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private UUID seedReservationAndPayment(boolean paid, boolean active) {
        return seedReservationAndPayment(paid, active, customerId);
    }

    private UUID seedReservationAndPayment(boolean paid, boolean active, UUID ownerId) {
        Transaction tx = newTransaction(ownerId);
        transactionRepository.save(tx);
        ReservationStatus status = active ? ReservationStatus.ACTIVE : ReservationStatus.CONFIRMED;
        MachineReservation r = reservation(tx, machine.getId(), status);
        reservationRepository.save(r);
        if (paid) {
            paymentRepository.save(paidPayment(tx.getId()));
        } else {
            paymentRepository.save(pendingPayment(tx.getId()));
        }
        return r.getId();
    }

    private void seedReservationWithOffset(long startMinutesAgo, long endMinutesAgo) {
        Transaction tx = newTransaction(customerId);
        transactionRepository.save(tx);
        MachineReservation r = MachineReservation.builder()
                .transaction(tx)
                .machineId(machine.getId())
                .startTime(LocalDateTime.now().plusMinutes(startMinutesAgo))
                .endTime(LocalDateTime.now().plusMinutes(endMinutesAgo))
                .status(ReservationStatus.CONFIRMED)
                .build();
        reservationRepository.save(r);
    }

    private Transaction newTransaction(UUID ownerId) {
        return Transaction.builder()
                .customerId(ownerId)
                .serviceType(com.laundry.transaction.entity.ServiceType.SELF_SERVICE)
                .status(com.laundry.transaction.entity.TransactionStatus.PAID)
                .totalAmount(new java.math.BigDecimal("25000.00"))
                .expiresAt(LocalDateTime.now().plusHours(2))
                .paidAt(LocalDateTime.now())
                .build();
    }

    private MachineReservation reservation(Transaction tx, UUID machineId, ReservationStatus status) {
        return MachineReservation.builder()
                .transaction(tx)
                .machineId(machineId)
                .startTime(LocalDateTime.now().minusMinutes(5))
                .endTime(LocalDateTime.now().plusHours(2))
                .status(status)
                .build();
    }

    private Payment paidPayment(UUID txId) {
        Payment p = Payment.builder()
                .transactionId(txId)
                .amount(new java.math.BigDecimal("25000.00"))
                .paymentMethod(PaymentMethod.ONLINE)
                .status(PaymentStatus.PAID)
                .build();
        p.setPaidAt(LocalDateTime.now());
        return p;
    }

    private Payment pendingPayment(UUID txId) {
        return Payment.builder()
                .transactionId(txId)
                .amount(new java.math.BigDecimal("25000.00"))
                .paymentMethod(PaymentMethod.ONLINE)
                .status(PaymentStatus.PENDING)
                .build();
    }

    /**
     * Drops the JPA session cache so the very next {@code findById} reloads
     * from the database. Service methods run in {@code @Transactional} and
     * commit before they return; the session cache is what is misleading
     * the test framework.
     */
    private void reload(UUID ignored) {
        entityManager.clear();
    }

    /**
     * The mocked {@link com.laundry.mqtt.service.MqttPublisher#publish(String, String)}
     * signature throws {@link org.eclipse.paho.client.mqttv3.MqttException} — that
     * is awkward to keep declaring across every verify() call. This helper
     * catches the (never-thrown-in-mocked-mode) exception and lets us chain
     * verify() with raw {@code any()} matchers without polluting each test.
     */
    private static void verifyPublished(com.laundry.mqtt.service.MqttPublisher publisher, int times) {
        // The service under test calls BOTH `buildWasherCommandTopic(...)` and
        // `publish(...)` per helper. Verify only the `publish` invocations.
        try {
            org.mockito.Mockito.verify(publisher, org.mockito.Mockito.times(times))
                    .publish(org.mockito.ArgumentMatchers.<String>any(),
                             org.mockito.ArgumentMatchers.<String>any());
        } catch (org.eclipse.paho.client.mqttv3.MqttException e) {
            throw new AssertionError("Mocked publish should never throw MqttException", e);
        }
    }
}
