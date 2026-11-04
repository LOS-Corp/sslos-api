package com.laundry.booking;

import com.laundry.shared.EmailService;
import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.service.BookingLifecycleService;
import com.laundry.booking.service.MachineSlotService;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.booking.service.TransferExpiryJob;
import com.laundry.machine.runtime.MachineRuntimeState;
import com.laundry.machine.service.MachineService;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import com.laundry.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/** SM-01 B10–B13, B22 and operator stops: a Wash–Dry order driven through device reports on PostgreSQL. */
@IntegrationTest
class BookingLifecycleIT {

    private static final long COMBO = 70_000;
    /** BR-10: 70,000 × 30,000 / (50,000 + 30,000). */
    private static final long DRYER_STAGE = 26_250;

    @Autowired private ScheduledBookingService bookings;
    @Autowired private BookingLifecycleService lifecycle;
    @Autowired private MachineSlotService slots;
    @Autowired private MachineService machineService;
    @Autowired private TransferExpiryJob transferExpiry;
    @Autowired private WalletService wallet;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private EmailService email;

    private UUID branchId;
    private UUID washerId;
    private UUID dryerId;
    private UUID customerId;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        branchId = Fixtures.bookableBranch(jdbc, 50_000);
        Fixtures.offering(jdbc, branchId, "DRY_ONLY", 30_000);
        Fixtures.offering(jdbc, branchId, "WASH_DRY", COMBO);
        washerId = Fixtures.washer(jdbc, branchId);
        dryerId = Fixtures.dryer(jdbc, branchId);
        customerId = Fixtures.customer(jdbc);
        var hold = bookings.createHold(customerId, new CreateHoldRequest(branchId, "WASH_DRY",
                Instant.now().plus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES),
                List.of(washerId, dryerId), "life-" + UUID.randomUUID(), null, null, null));
        bookingId = hold.bookingId();
        Fixtures.paidPayment(jdbc, hold.transactionId(), COMBO);
        bookings.confirmPayment(hold.transactionId());
    }

    @Test
    @DisplayName("B7, B10, B11, B13: washer, transfer, dryer, then one completion email and no refund")
    void wholeWashDryCycle() {
        start(washerId);
        assertThat(status()).isEqualTo("IN_PROGRESS");

        report(washerId, "COMPLETED");
        assertThat(status()).isEqualTo("TRANSFER_PENDING");

        start(dryerId);
        assertThat(status()).isEqualTo("IN_PROGRESS");

        report(dryerId, "COMPLETED");
        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(wallet.balanceOf(customerId).vnd()).isZero();
        verify(email, timeout(2000).times(1)).sendLaundryCompletedEmail(any());
    }

    @Test
    @DisplayName("SC-28 / SC-37 / B12: dryer not started in time → released, stage refunded, one violation, one email")
    void dryerNotStarted() {
        start(washerId);
        report(washerId, "COMPLETED");

        transferExpiry.sweep();
        assertThat(status()).as("the transfer time has not passed").isEqualTo("TRANSFER_PENDING");

        transferTimePassed();
        transferExpiry.sweep();

        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(allocationActive(dryerId)).as("dryer released").isFalse();
        assertThat(violations()).isEqualTo(1);
        assertThat(wallet.balanceOf(customerId).vnd()).as("first violation: 100 % of the dryer stage").isEqualTo(DRYER_STAGE);
        verify(email, timeout(2000).times(1)).sendLaundryCompletedEmail(any());
    }

    @Test
    @DisplayName("BR-13: the dryer itself was offline → 100 % of the stage and no violation")
    void dryerUnavailable() {
        start(washerId);
        report(washerId, "COMPLETED");
        jdbc.update("UPDATE machines SET online = FALSE WHERE id = ?", dryerId);
        transferTimePassed();

        transferExpiry.sweep();

        assertThat(status()).isEqualTo("COMPLETED");
        assertThat(violations()).isZero();
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(DRYER_STAGE);
    }

    @Test
    @DisplayName("BR-13 / FR-REF-07: an operator stops the washer → cancelled, 100 % back, no violation, no completion email")
    void operatorStopsTheFirstStage() {
        start(washerId);
        command(washerId, "STOP", UUID.randomUUID());

        report(washerId, "AVAILABLE");

        assertThat(status()).isEqualTo("CANCELLED");
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(COMBO);
        assertThat(violations()).isZero();
        assertThat(allocationActive(dryerId)).isFalse();
        verify(email, never()).sendLaundryCompletedEmail(any());
    }

    @Test
    @DisplayName("BR-10: an operator stops the dryer after the washer finished → only the dryer stage comes back")
    void operatorStopsTheSecondStage() {
        start(washerId);
        report(washerId, "COMPLETED");
        start(dryerId);
        command(dryerId, "STOP", UUID.randomUUID());

        report(dryerId, "AVAILABLE");

        assertThat(status()).isEqualTo("CANCELLED");
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(DRYER_STAGE);
    }

    @Test
    @DisplayName("A customer stopping their own cycle used that stage: the booking moves on with no refund")
    void customerStopsTheirOwnCycle() {
        start(washerId);
        command(washerId, "STOP", customerId);

        report(washerId, "AVAILABLE");

        assertThat(status()).isEqualTo("TRANSFER_PENDING");
        assertThat(wallet.balanceOf(customerId).vnd()).isZero();
    }

    @Test
    @DisplayName("A lost COMPLETED message: back to AVAILABLE with no stop command still finishes the cycle")
    void lostCompletedMessage() {
        start(washerId);

        report(washerId, "AVAILABLE");

        assertThat(status()).isEqualTo("TRANSFER_PENDING");
    }

    @Test
    @DisplayName("B22: the sweep closes a cycle whose completion never arrived, once its window is over")
    void completionSweep() {
        jdbc.update("""
                UPDATE booking_resource_allocations SET cycle_state = 'DONE' WHERE booking_id = ? AND resource_id = ?
                """, bookingId, dryerId);
        start(washerId);
        jdbc.update("UPDATE machines SET status = 'AVAILABLE' WHERE id = ?", washerId);
        jdbc.update("""
                UPDATE booking_resource_allocations SET occupied_from = NOW() - interval '3 hours',
                    occupied_until = NOW() - interval '2 hours'
                WHERE booking_id = ? AND resource_id = ?
                """, bookingId, washerId);

        lifecycle.sweepFinishedReservations();

        assertThat(status()).isEqualTo("COMPLETED");
        verify(email, timeout(2000).times(1)).sendLaundryCompletedEmail(any());
    }

    private void start(UUID machineId) {
        UUID allocationId = jdbc.queryForObject("""
                SELECT id FROM booking_resource_allocations WHERE booking_id = ? AND resource_id = ?
                """, UUID.class, bookingId, machineId);
        assertThat(slots.markRunning(allocationId)).isTrue();
        lifecycle.onCycleStarted(allocationId);
        jdbc.update("UPDATE machines SET status = 'RUNNING' WHERE id = ?", machineId);
    }

    private void report(UUID machineId, String deviceStatus) {
        String code = jdbc.queryForObject("SELECT machine_code FROM machines WHERE id = ?", String.class, machineId);
        machineService.processStatus(branchId, code, MachineRuntimeState.builder().status(deviceStatus).build());
    }

    private void command(UUID machineId, String command, UUID issuedBy) {
        jdbc.update("""
                INSERT INTO device_commands (id, command_id, machine_id, machine_code, command, issued_by_user_id, status)
                SELECT gen_random_uuid(), ?, id, machine_code, ?, ?, 'ACKNOWLEDGED' FROM machines WHERE id = ?
                """, "CMD-" + UUID.randomUUID(), command, issuedBy, machineId);
    }

    private void transferTimePassed() {
        jdbc.update("UPDATE scheduled_bookings SET updated_at = NOW() - interval '11 minutes' WHERE id = ?", bookingId);
        jdbc.update("""
                UPDATE booking_resource_allocations SET occupied_from = NOW() - interval '1 minute'
                WHERE booking_id = ? AND resource_id = ?
                """, bookingId, dryerId);
    }

    private boolean allocationActive(UUID machineId) {
        return jdbc.queryForObject("""
                SELECT active FROM booking_resource_allocations WHERE booking_id = ? AND resource_id = ?
                """, Boolean.class, bookingId, machineId);
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM scheduled_bookings WHERE id = ?", String.class, bookingId);
    }

    private int violations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM policy_violations WHERE customer_id = ?", Integer.class, customerId);
    }
}
