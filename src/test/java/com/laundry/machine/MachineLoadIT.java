package com.laundry.machine;

import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineDeploymentStatus;
import com.laundry.machine.entity.MachineStartAuthorization;
import com.laundry.machine.entity.MachineStartAuthorizationStatus;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.exception.StaleAuthorizationException;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import com.laundry.machine.service.MachineLoadService;
import com.laundry.machine.service.MachineStartAuthorizationService;
import com.laundry.payment.service.PaymentService;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/** Wash by weight (V61): the washer weighs the laundry before START. */
@IntegrationTest
@DisplayName("Weighing before START")
class MachineLoadIT {

    private static final String BOOT = "boot-1";

    @Autowired private MachineStartAuthorizationService authorizations;
    @Autowired private MachineLoadService loads;
    @Autowired private PaymentService payments;
    @Autowired private MachineRepository machineRepository;
    @Autowired private MachineStartAuthorizationRepository authorizationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.laundry.devicelink.DeviceLink deviceLink;

    private UUID customerId;

    @BeforeEach
    void setUp() {
        reset(deviceLink);
        Fixtures.bookableStore(jdbc, 10_000);
        // One store for every test: earlier tests' machines stay out of the way.
        jdbc.update("UPDATE machines SET online = FALSE");
        customerId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Up to 0.5 kg over the paid weight is free; START goes through")
    void withinToleranceIsAccepted() {
        Machine washer = washer("7");
        UUID auth = unlock(washer, 5);

        authorizations.handleLoadWeighed(washer, auth, new BigDecimal("5.45"), BOOT);

        MachineStartAuthorization row = authorizationRepository.findById(auth).orElseThrow();
        assertThat(row.getLoadVerdict()).isEqualTo("ACCEPTED");
        assertThat(row.getLoadExtraKg()).isZero();
        assertThat(lastSent()).contains("\"type\":\"LOAD_VERDICT\"").contains("\"accepted\":true");
        assertThat(authorizations.handleStartRequest(washer, auth, "req-1", BOOT)).isNotNull();
    }

    @Test
    @DisplayName("V65: self-service is priced per cycle, so any weight is accepted: no top-up, no move")
    void anyWeightIsAccepted() {
        Machine washer = washer("7");
        UUID auth = unlock(washer, 5);

        // More than paid and more than the capacity: still accepted.
        authorizations.handleLoadWeighed(washer, auth, new BigDecimal("9.1"), BOOT);

        MachineStartAuthorization row = authorizationRepository.findById(auth).orElseThrow();
        assertThat(row.getLoadVerdict()).isEqualTo("ACCEPTED");
        assertThat(row.getLoadExtraKg()).isZero();
        assertThat(lastSent()).contains("\"accepted\":true");
        assertThatThrownBy(() -> loads.payExtra(auth, customerId, true)).isInstanceOf(RuntimeException.class);
        assertThat(authorizations.handleStartRequest(washer, auth, "req-1", BOOT)).isNotNull();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Machine washer(String capacityKg) {
        return machineRepository.save(Machine.builder()
                .machineCode("WL" + UUID.randomUUID().toString().substring(0, 6).toUpperCase())
                .type(MachineType.WASHER)
                .status(MachineStatus.AVAILABLE)
                .deploymentStatus(MachineDeploymentStatus.ACTIVE)
                .online(true)
                .lastHeartbeatAt(OffsetDateTime.now().plusDays(1))
                .capacityKg(new BigDecimal(capacityKg))
                .build());
    }

    /** A paid WASH_ONLY booking for {@code paidKg} kg on {@code washer} starting now, checked in and acknowledged. */
    private UUID unlock(Machine washer, int paidKg) {
        UUID transactionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions (id, customer_id, service_type, status, total_amount, paid_at)
                VALUES (?, ?, 'SELF_SERVICE', 'PAID', ?, NOW())
                """, transactionId, customerId, 10_000L * paidKg);
        Fixtures.paidPayment(jdbc, transactionId, 10_000L * paidKg);
        Instant start = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant end = start.plus(45, ChronoUnit.MINUTES);
        UUID bookingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO scheduled_bookings (id, transaction_id, service_code, status, start_time,
                                                expected_end_time, hold_expires_at, timeline, declared_load_kg)
                VALUES (?, ?, 'WASH_ONLY', 'CONFIRMED', ?, ?, ?, '[]'::jsonb, ?)
                """, bookingId, transactionId, utc(start), utc(end), utc(start), paidKg);
        jdbc.update("""
                INSERT INTO booking_resource_allocations
                  (booking_id, resource_id, resource_type, phase, occupied_from, occupied_until, cycle_state)
                VALUES (?, ?, 'WASHER', 'WASHING', ?, ?, 'PENDING')
                """, bookingId, washer.getId(), utc(start), utc(end));
        UUID auth = authorizations.checkIn(washer.getMachineCode(), customerId).authorizationId();
        authorizations.handleAuthAck(washer, auth, "AUTH-" + auth, true, "ACCEPTED", "", BOOT);
        return auth;
    }

    private String lastSent() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(deviceLink, atLeastOnce()).send(any(), payload.capture());
        return payload.getValue();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
