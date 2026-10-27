package com.laundry.booking;

import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.service.NoShowJob;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import com.laundry.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** UC-28 / BR-07 / BR-12 / BR-13 / BR-17: check-in or drop-off window closed with nobody showing up. */
@IntegrationTest
class NoShowIT {

    private static final long PRICE = 40_000;

    @Autowired private ScheduledBookingService bookings;
    @Autowired private NoShowJob noShowJob;
    @Autowired private WalletService wallet;
    @Autowired private JdbcTemplate jdbc;

    private UUID branchId;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        branchId = Fixtures.bookableBranch(jdbc, PRICE);
        customerId = Fixtures.customer(jdbc);
    }

    @Test
    @DisplayName("First no-show → NO_SHOW, slot released, violation recorded, 100 %; second → 70 %")
    void customerNoShows() {
        UUID first = confirmedBookingThatStarted(30, Fixtures.washer(jdbc, branchId));
        noShowJob.sweep();

        assertThat(status(first)).isEqualTo("NO_SHOW");
        assertThat(jdbc.queryForObject("SELECT bool_or(active) FROM booking_resource_allocations WHERE booking_id = ?",
                Boolean.class, first)).as("machine released").isFalse();
        assertThat(violations()).isEqualTo(1);
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);

        UUID second = confirmedBookingThatStarted(25, Fixtures.washer(jdbc, branchId));
        noShowJob.sweep();

        assertThat(status(second)).isEqualTo("NO_SHOW");
        assertThat(violations()).isEqualTo(2);
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE + PRICE * 70 / 100);

        noShowJob.sweep();
        assertThat(wallet.balanceOf(customerId).vnd()).as("idempotent").isEqualTo(PRICE + PRICE * 70 / 100);
    }

    @Test
    @DisplayName("BR-13: the booked machine was offline → system failure, 100 % and no violation")
    void machineFailureIsNotTheCustomersFault() {
        UUID washer = Fixtures.washer(jdbc, branchId);
        UUID booking = confirmedBookingThatStarted(30, washer);
        jdbc.update("UPDATE machines SET online = FALSE, status = 'OFFLINE' WHERE id = ?", washer);

        noShowJob.sweep();

        assertThat(status(booking)).isEqualTo("NO_SHOW");
        assertThat(violations()).isZero();
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
    }

    @Test
    @DisplayName("A booking still inside its check-in window is left alone")
    void openWindowIsUntouched() {
        UUID booking = confirmedBookingThatStarted(5, Fixtures.washer(jdbc, branchId));
        noShowJob.sweep();
        assertThat(status(booking)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("BR-17: assisted laundry never dropped off → NO_SHOW after T+60, compartment released, violation")
    void assistedDropOffMissed() {
        Fixtures.Locker locker = Fixtures.lockerCompartment(jdbc, branchId, PRICE);
        UUID inWindow = confirmedBookingThatStarted(45, "ASSISTED_LOCKER", locker.compartmentId());
        noShowJob.sweep();
        assertThat(status(inWindow)).as("drop-off still allowed until T+60").isEqualTo("CONFIRMED");

        jdbc.update("UPDATE scheduled_bookings SET start_time = NOW() - interval '61 minutes' WHERE id = ?", inWindow);
        noShowJob.sweep();

        assertThat(status(inWindow)).isEqualTo("NO_SHOW");
        assertThat(jdbc.queryForObject("SELECT bool_or(active) FROM booking_resource_allocations WHERE booking_id = ?",
                Boolean.class, inWindow)).as("compartment released").isFalse();
        assertThat(violations()).isEqualTo(1);
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
    }

    @Test
    @DisplayName("BR-17: laundry already in the locker (CHECKED_IN) is never a no-show")
    void assistedDroppedOffIsUntouched() {
        Fixtures.Locker locker = Fixtures.lockerCompartment(jdbc, branchId, PRICE);
        UUID booking = confirmedBookingThatStarted(90, "ASSISTED_LOCKER", locker.compartmentId());
        jdbc.update("UPDATE scheduled_bookings SET status = 'CHECKED_IN' WHERE id = ?", booking);

        noShowJob.sweep();

        assertThat(status(booking)).isEqualTo("CHECKED_IN");
        assertThat(violations()).isZero();
    }

    @Test
    @DisplayName("BR-13: the locker cabinet was offline → system failure, 100 % and no violation")
    void lockerOfflineIsNotTheCustomersFault() {
        Fixtures.Locker locker = Fixtures.lockerCompartment(jdbc, branchId, PRICE);
        UUID booking = confirmedBookingThatStarted(61, "ASSISTED_LOCKER", locker.compartmentId());
        jdbc.update("UPDATE machines SET online = FALSE, status = 'OFFLINE' WHERE id = ?", locker.cabinetId());

        noShowJob.sweep();

        assertThat(status(booking)).isEqualTo("NO_SHOW");
        assertThat(violations()).isZero();
        assertThat(wallet.balanceOf(customerId).vnd()).isEqualTo(PRICE);
    }

    private UUID confirmedBookingThatStarted(int minutesAgo, UUID washer) {
        return confirmedBookingThatStarted(minutesAgo, "WASH_ONLY", washer);
    }

    /** A paid, confirmed booking whose start was {@code minutesAgo} minutes ago. */
    private UUID confirmedBookingThatStarted(int minutesAgo, String service, UUID resource) {
        Instant start = Instant.now().plus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        var hold = bookings.createHold(customerId, new CreateHoldRequest(branchId, service, start, List.of(resource),
                "noshow-" + UUID.randomUUID(), null, null, null));
        bookings.confirmPayment(hold.transactionId());
        jdbc.update("""
                UPDATE scheduled_bookings
                SET start_time = NOW() - make_interval(mins => ?),
                    expected_end_time = NOW() - make_interval(mins => ?) + interval '45 minutes'
                WHERE id = ?
                """, minutesAgo, minutesAgo, hold.bookingId());
        jdbc.update("UPDATE transactions SET paid_at = (NOW() AT TIME ZONE 'UTC') - make_interval(mins => ?) WHERE id = ?",
                minutesAgo + 5, hold.transactionId());
        return hold.bookingId();
    }

    private String status(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM scheduled_bookings WHERE id = ?", String.class, bookingId);
    }

    private int violations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM policy_violations WHERE customer_id = ?", Integer.class,
                customerId);
    }
}
