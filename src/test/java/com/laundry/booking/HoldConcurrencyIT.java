package com.laundry.booking;

import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.branch.entity.Branch;
import com.laundry.branch.repository.BranchRepository;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineDeploymentStatus;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BR-02 / NFR-06: one machine interval can be held by at most one booking, under real concurrency. */
@IntegrationTest
class HoldConcurrencyIT {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final int CUSTOMERS = 20;

    @Autowired private ScheduledBookingService bookings;
    @Autowired private BranchRepository branches;
    @Autowired private MachineRepository machines;
    @Autowired private JdbcTemplate jdbc;

    private UUID branchId;
    private UUID washerId;
    private Instant start;

    @BeforeEach
    void setUp() {
        branchId = branches.save(Branch.builder().name("Hold " + UUID.randomUUID()).build()).getId();
        jdbc.update("INSERT INTO branch_booking_settings (branch_id, timezone) VALUES (?, ?)", branchId, ZONE.getId());
        for (int day = 1; day <= 7; day++) {
            jdbc.update("INSERT INTO branch_booking_hours (branch_id, day_of_week, opens_at, closes_at) VALUES (?, ?, ?, ?)",
                    branchId, day, LocalTime.of(6, 0), LocalTime.of(22, 0));
        }
        jdbc.update("INSERT INTO branch_service_offerings (branch_id, service_code, enabled, price) VALUES (?, 'WASH_ONLY', TRUE, 30000)",
                branchId);
        washerId = machines.save(Machine.builder()
                .machineCode("H" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .branchId(branchId)
                .type(MachineType.WASHER)
                .status(MachineStatus.AVAILABLE)
                .deploymentStatus(MachineDeploymentStatus.ACTIVE)
                .online(true)
                .build()).getId();
        start = LocalDate.now(ZONE).plusDays(1).atTime(10, 0).atZone(ZONE).toInstant().truncatedTo(ChronoUnit.MINUTES);
    }

    @Test
    @DisplayName("SC-01: twenty customers hold the same washer interval at once → exactly one succeeds")
    void onlyOneConcurrentHoldWins() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CUSTOMERS);
        try {
            List<Future<Boolean>> results = java.util.stream.IntStream.range(0, CUSTOMERS)
                    .mapToObj(i -> pool.submit(() -> {
                        go.await();
                        try {
                            bookings.createHold(UUID.randomUUID(), hold("sc01-" + i));
                            return true;
                        } catch (RuntimeException rejected) {
                            return false;
                        }
                    }))
                    .toList();
            go.countDown();
            long winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) winners++;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM booking_resource_allocations WHERE resource_id = ? AND active", Integer.class,
                washerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("NFR-06: the database refuses an overlapping live allocation even if the application check is bypassed")
    void databaseConstraintIsTheLastLineOfDefence() {
        bookings.createHold(UUID.randomUUID(), hold("first"));
        UUID booking = jdbc.queryForObject("SELECT booking_id FROM booking_resource_allocations WHERE resource_id = ?",
                UUID.class, washerId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO booking_resource_allocations (booking_id, resource_id, resource_type, phase, occupied_from, occupied_until)
                SELECT booking_id, resource_id, resource_type, phase, occupied_from + interval '5 minutes', occupied_until
                FROM booking_resource_allocations WHERE booking_id = ?
                """, booking))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Cancelling a hold frees the interval for the next customer (trigger keeps allocations in step)")
    void cancelledHoldFreesTheSlot() {
        UUID customer = UUID.randomUUID();
        var first = bookings.createHold(customer, hold("cancel-1"));
        bookings.cancelBooking(customer, first.bookingId());

        assertThat(bookings.createHold(UUID.randomUUID(), hold("cancel-2")).status()).isEqualTo("HELD");
    }

    @Test
    @DisplayName("SC-17: payment inside the hold confirms; payment after the hold goes to review and never re-takes the slot")
    void latePaymentGoesToReview() {
        var onTime = bookings.createHold(UUID.randomUUID(), hold("pay-on-time"));
        bookings.confirmPayment(onTime.transactionId());
        assertThat(status(onTime.bookingId())).isEqualTo("CONFIRMED");

        Instant otherStart = start.plus(2, ChronoUnit.HOURS);
        UUID lateCustomer = Fixtures.customer(jdbc);
        var late = bookings.createHold(lateCustomer, new CreateHoldRequest(branchId, "WASH_ONLY", otherStart,
                List.of(washerId), "pay-late-" + UUID.randomUUID(), null, null, null));
        // The hold lapses (second 310 of a 300 s hold) while the slot is still free.
        jdbc.update("UPDATE scheduled_bookings SET hold_expires_at = NOW() - interval '10 seconds' WHERE id = ?",
                late.bookingId());
        bookings.confirmPayment(late.transactionId());

        assertThat(status(late.bookingId())).isEqualTo("PAYMENT_REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT bool_or(active) FROM booking_resource_allocations WHERE booking_id = ?",
                Boolean.class, late.bookingId())).as("slot released, not re-taken").isFalse();
        assertThat(jdbc.queryForObject("SELECT balance_vnd FROM wallets WHERE customer_id = ?", Long.class,
                lateCustomer)).as("MSG11: the payment goes to the wallet").isEqualTo(30000L);
    }

    private String status(UUID bookingId) {
        return jdbc.queryForObject("SELECT status FROM scheduled_bookings WHERE id = ?", String.class, bookingId);
    }

    private CreateHoldRequest hold(String key) {
        return new CreateHoldRequest(branchId, "WASH_ONLY", start, List.of(washerId), key + "-" + UUID.randomUUID(),
                null, null, null);
    }
}
