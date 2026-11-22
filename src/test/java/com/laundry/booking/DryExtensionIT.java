package com.laundry.booking;

import com.laundry.booking.service.DryExtensionService;
import com.laundry.machine.dto.PriceProfileDtos.PriceProfileRequest;
import com.laundry.machine.dto.PriceProfileDtos.PriceProfileView;
import com.laundry.machine.dto.UpdateMachineRequest;
import com.laundry.machine.service.MachineCatalogService;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.service.MachineService;
import com.laundry.machine.service.MachineStartAuthorizationService;
import com.laundry.machine.service.PriceProfileService;
import com.laundry.shared.error.BusinessException;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import com.laundry.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
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

/** V66: extra drying time bought while the dryer runs; V67: the pulses that open a retrofit machine. */
@IntegrationTest
@DisplayName("Extra drying time and retrofit pulses")
class DryExtensionIT {

    @Autowired private DryExtensionService extensions;
    @Autowired private PriceProfileService profiles;
    @Autowired private MachineCatalogService catalog;
    @Autowired private MachineService machineService;
    @Autowired private MachineStartAuthorizationService authorizations;
    @Autowired private WalletService wallet;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.laundry.devicelink.DeviceLink deviceLink;

    private UUID customerId;
    private UUID dryer;
    private String code;

    @BeforeEach
    void setUp() {
        reset(deviceLink);
        Fixtures.bookableStore(jdbc, 30_000);
        customerId = Fixtures.customer(jdbc);
        dryer = Fixtures.dryer(jdbc);
        code = jdbc.queryForObject("SELECT machine_code FROM machines WHERE id = ?", String.class, dryer);
        PriceProfileView profile = profiles.create(new PriceProfileRequest("Sấy 13kg " + suffix(), MachineType.DRYER,
                new BigDecimal("54000"), null, 12, new BigDecimal("18000")));
        jdbc.update("UPDATE machines SET price_profile_id = ?, status = 'RUNNING' WHERE id = ?", profile.id(), dryer);
        // V67: the pulses of a retrofit board belong to the machine.
        catalog.updateMachine(code, UpdateMachineRequest.builder().vendPulses(9).extraVendPulses(2).build());
    }

    @Test
    @DisplayName("Paid extra time holds the dryer longer and goes to the board; the board's ACK applies it")
    void paidExtraTimeIsSentAndApplied() {
        Instant until = runningSlot(dryer, customerId);

        var offer = extensions.offer(code, customerId);
        assertThat(offer.available()).isTrue();
        assertThat(offer.minutes()).isEqualTo(12);
        assertThat(offer.price()).isEqualByComparingTo("18000");

        var extension = extensions.prepare(code, customerId);
        assertThat(extensions.prepare(code, customerId).transactionId()).isEqualTo(extension.transactionId());
        Fixtures.paidPayment(jdbc, extension.transactionId(), 18_000);
        extensions.confirmPayment(extension.transactionId());

        assertThat(slotUntil(extension.allocationId())).isEqualTo(until.plus(Duration.ofMinutes(12)));
        assertThat(status(extension.transactionId())).isEqualTo("PAID");
        assertThat(lastSent()).contains("\"type\":\"EXTEND_CYCLE\"").contains("\"extraSeconds\":720")
                .contains("\"vendPulses\":2");

        machineService.processCommandAck(code, "EXT-" + extension.transactionId(), true, "ACCEPTED", null, null);
        assertThat(status(extension.transactionId())).isEqualTo("APPLIED");
        assertThat(extensions.offer(code, customerId).boughtMinutes()).isEqualTo(12);
    }

    @Test
    @DisplayName("A board that refuses the extra time gives the slot back and the money to the wallet")
    void refusedExtraTimeIsRefunded() {
        Instant until = runningSlot(dryer, customerId);
        var extension = extensions.prepare(code, customerId);
        Fixtures.paidPayment(jdbc, extension.transactionId(), 18_000);
        extensions.confirmPayment(extension.transactionId());

        machineService.processCommandAck(code, "EXT-" + extension.transactionId(), false, "REJECTED", null, "NOT_DRYING");

        assertThat(status(extension.transactionId())).isEqualTo("REFUNDED");
        assertThat(slotUntil(extension.allocationId())).isEqualTo(until);
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(18_000);
    }

    @Test
    @DisplayName("No extra time when the next booking needs the dryer, or when the dryer is not running")
    void refusedWhenNextBookingOrIdle() {
        Instant until = runningSlot(dryer, customerId);
        booking(dryer, Fixtures.customer(jdbc), until.plus(Duration.ofMinutes(5)), until.plus(Duration.ofMinutes(50)),
                "CONFIRMED", "PENDING");
        assertThat(extensions.offer(code, customerId).available()).isFalse();
        assertThatThrownBy(() -> extensions.prepare(code, customerId)).isInstanceOf(BusinessException.class);

        jdbc.update("DELETE FROM booking_resource_allocations WHERE resource_id = ? AND cycle_state = 'PENDING'", dryer);
        jdbc.update("UPDATE machines SET status = 'AVAILABLE' WHERE id = ?", dryer);
        assertThat(extensions.offer(code, customerId).available()).isFalse();
    }

    @Test
    @DisplayName("Paid after the cycle ended: nothing is sent and the money goes to the wallet")
    void lateExtraTimeIsRefunded() {
        runningSlot(dryer, customerId);
        var extension = extensions.prepare(code, customerId);
        jdbc.update("UPDATE booking_resource_allocations SET cycle_state = 'DONE' WHERE id = ?", extension.allocationId());
        Fixtures.paidPayment(jdbc, extension.transactionId(), 18_000);
        reset(deviceLink);

        extensions.confirmPayment(extension.transactionId());

        assertThat(status(extension.transactionId())).isEqualTo("REFUNDED");
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(18_000);
        verify(deviceLink, org.mockito.Mockito.never()).send(any(), any());
    }

    @Test
    @DisplayName("Profiles: only dryers sell extra time, minutes and price go together; the machine's pulses go with START")
    void profileRulesAndPulses() {
        assertThatThrownBy(() -> profiles.create(new PriceProfileRequest("Giặt " + suffix(), MachineType.WASHER,
                new BigDecimal("30000"), null, 10, new BigDecimal("10000"))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> profiles.create(new PriceProfileRequest("Sấy lỗi " + suffix(), MachineType.DRYER,
                new BigDecimal("30000"), null, 10, null))).isInstanceOf(BusinessException.class);

        UUID other = Fixtures.customer(jdbc);
        jdbc.update("UPDATE machines SET status = 'AVAILABLE' WHERE id = ?", dryer);
        Instant start = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        booking(dryer, other, start, start.plus(Duration.ofMinutes(45)), "CONFIRMED", "PENDING");
        authorizations.checkIn(code, other);
        // V69: no program, only the profile's cycle (the dryer's 45-minute package).
        assertThat(lastSent()).contains("\"type\":\"AUTHORIZE_START\"").contains("\"vendPulses\":9")
                .contains("\"cycleSeconds\":2700").doesNotContain("\"program\"");

        // Back to the default: no pulses sent, the board uses 1.
        assertThat(catalog.updateMachine(code, UpdateMachineRequest.builder().clearVendPulses(true).build())
                .getVendPulses()).isNull();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** The customer's dryer cycle running now; returns the end of its slot. */
    private Instant runningSlot(UUID machineId, UUID customer) {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MINUTES).minus(Duration.ofMinutes(10));
        Instant end = start.plus(Duration.ofMinutes(45));
        booking(machineId, customer, start, end, "IN_PROGRESS", "RUNNING");
        return end;
    }

    private void booking(UUID machineId, UUID customer, Instant start, Instant end, String status, String cycleState) {
        UUID transactionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions (id, customer_id, service_type, status, total_amount, paid_at)
                VALUES (?, ?, 'SELF_SERVICE', 'PAID', 54000, NOW())
                """, transactionId, customer);
        Fixtures.paidPayment(jdbc, transactionId, 54_000);
        UUID bookingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO scheduled_bookings (id, transaction_id, service_code, status, start_time,
                                                expected_end_time, hold_expires_at, timeline)
                VALUES (?, ?, 'WASH_ONLY', ?, ?, ?, ?, '[]'::jsonb)
                """, bookingId, transactionId, status, utc(start), utc(end), utc(start));
        jdbc.update("""
                INSERT INTO booking_resource_allocations
                  (booking_id, resource_id, resource_type, phase, occupied_from, occupied_until, cycle_state)
                VALUES (?, ?, 'DRYER', 'DRYING', ?, ?, ?)
                """, bookingId, machineId, utc(start), utc(end), cycleState);
    }

    private Instant slotUntil(UUID allocationId) {
        return jdbc.queryForObject("SELECT occupied_until FROM booking_resource_allocations WHERE id = ?",
                OffsetDateTime.class, allocationId).toInstant();
    }

    private String status(UUID transactionId) {
        return jdbc.queryForObject("SELECT status FROM dry_extensions WHERE transaction_id = ?", String.class, transactionId);
    }

    private String lastSent() {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(deviceLink, atLeastOnce()).send(any(), payload.capture());
        return payload.getValue();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
