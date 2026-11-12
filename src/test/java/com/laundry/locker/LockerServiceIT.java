package com.laundry.locker;

import com.laundry.audit.repository.AuditLogRepository;
import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.locker.dto.LockerDtos.CustomerLockerAccess;
import com.laundry.locker.service.LockerService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.security.CurrentUser;
import com.laundry.shared.security.Role;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BR-18, BR-25, FR-ACT-11, NFR-14: staff openings and the pickup window of the Smart Locker. */
@IntegrationTest
class LockerServiceIT {

    @Autowired private LockerService lockers;
    @Autowired private ScheduledBookingService bookings;
    @Autowired private AuditLogRepository auditLogs;
    @Autowired private JdbcTemplate jdbc;

    private Fixtures.Locker locker;
    private CurrentUser owner;
    private UUID customerId;
    private UUID bookingId;

    @BeforeEach
    void setUp() {
        Fixtures.bookableStore(jdbc, 50_000);
        locker = Fixtures.lockerCompartment(jdbc, 80_000);
        owner = CurrentUser.of(Fixtures.user(jdbc, "OWNER"), Role.OWNER);
        customerId = Fixtures.customer(jdbc);
        var hold = bookings.createHold(customerId, new CreateHoldRequest("ASSISTED_LOCKER",
                Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES),
                List.of(locker.compartmentId()), "locker-" + UUID.randomUUID(), null, null, null));
        bookingId = hold.bookingId();
        Fixtures.paidPayment(jdbc, hold.transactionId(), 80_000);
        bookings.confirmPayment(hold.transactionId());
    }

    @Test
    @DisplayName("BR-25: opening a cell to inspect it needs a reason and is audited")
    void inspectNeedsAReason() {
        assertThatThrownBy(() -> lockers.staffOpen(locker.cabinetId(), 1, "INSPECT", " ", owner))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.REASON_REQUIRED));

        lockers.staffOpen(locker.cabinetId(), 1, "INSPECT", "Khách báo quên đồ", owner);

        assertThat(auditLogs.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("LOCKER", locker.compartmentId().toString()))
                .singleElement().satisfies(entry -> {
                    assertThat(entry.getAction()).isEqualTo("LOCKER_OPENED");
                    assertThat(entry.getReason()).isEqualTo("Khách báo quên đồ");
                    assertThat(entry.getActorId()).isEqualTo(owner.id());
                });
    }

    @Test
    @DisplayName("FR-AST-04: collecting an order is a step of the order, audited with no reason typed")
    void collectIsAudited() {
        jdbc.update("UPDATE scheduled_bookings SET status = 'CHECKED_IN' WHERE id = ?", bookingId);

        lockers.staffOpen(locker.cabinetId(), 1, "COLLECT", null, owner);

        assertThat(auditLogs.findByEntityTypeAndEntityIdOrderByOccurredAtAsc("LOCKER", locker.compartmentId().toString()))
                .singleElement().satisfies(entry -> assertThat(entry.getReason()).isEqualTo("Order step COLLECT"));
    }

    @Test
    @DisplayName("BR-18: the free pickup runs from READY, and the cell stays held for all of it")
    void pickupWindowRunsFromReady() {
        readyForPickup();

        CustomerLockerAccess access = lockers.customerAccess(customerId, bookingId);

        assertThat(access.stage()).isEqualTo("PICKUP");
        Instant until = pinValidUntil();
        assertThat(Duration.between(Instant.now(), until)).isBetween(Duration.ofHours(47), Duration.ofHours(48));
        assertThat(jdbc.queryForObject("SELECT occupied_until FROM booking_resource_allocations WHERE booking_id = ?",
                java.sql.Timestamp.class, bookingId).toInstant()).isAfterOrEqualTo(until.minusSeconds(1));
    }

    @Test
    @DisplayName("BR-18: an expired pickup PIN is replaced, so the order does not stay stuck in the locker")
    void expiredPickupPinIsReplaced() {
        readyForPickup();
        lockers.customerAccess(customerId, bookingId);
        String expired = jdbc.queryForObject("SELECT pin FROM locker_accesses WHERE booking_id = ?", String.class, bookingId);
        jdbc.update("UPDATE locker_accesses SET valid_until = NOW() - interval '1 minute' WHERE booking_id = ?", bookingId);

        lockers.customerAccess(customerId, bookingId);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM locker_accesses WHERE booking_id = ? AND status = 'REVOKED' AND pin = ?",
                Integer.class, bookingId, expired)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM locker_accesses WHERE booking_id = ? AND status = 'ACTIVE' AND valid_until > NOW()",
                Integer.class, bookingId)).isEqualTo(1);
    }

    private void readyForPickup() {
        jdbc.update("UPDATE scheduled_bookings SET status = 'READY_FOR_PICKUP' WHERE id = ?", bookingId);
    }

    private Instant pinValidUntil() {
        return jdbc.queryForObject("SELECT valid_until FROM locker_accesses WHERE booking_id = ? AND status = 'ACTIVE'",
                java.sql.Timestamp.class, bookingId).toInstant();
    }
}
