package com.laundry.booking.service;

import com.laundry.shared.scheduling.JobLock;
import com.laundry.shared.EmailService;
import com.laundry.shared.LaundryCompletedEmail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Moves a scheduled booking through its usage phase, driven by the machine:
 * <pre>
 * CONFIRMED ──START accepted──▶ IN_PROGRESS ──last machine finished──▶ COMPLETED (+ email)
 *                                   │ washer done, dryer still booked
 *                                   ▼
 *                            TRANSFER_PENDING ──dryer START──▶ IN_PROGRESS
 * </pre>
 * A cycle counts as finished when the machine leaves RUNNING/PAUSED for
 * COMPLETED or AVAILABLE. Each washer/dryer allocation carries its own cycle state
 * (PENDING → RUNNING → DONE, V51); {@link #sweepFinishedReservations()} closes cycles
 * whose completion message was lost.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingLifecycleService {

    private static final String OPEN_BOOKING_STATUSES =
            "('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING', 'READY_FOR_PICKUP')";

    private final JdbcTemplate jdbc;
    private final JobLock jobLock;
    private final EmailService emailService;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final com.laundry.config.BookingProperties props;

    /**
     * A machine accepted START for the allocation {@code allocationId}: the customer's booking is now in use.
     * Runs after the caller's transaction commits, in its own transaction, so a
     * booking problem can never roll back the machine's own state change.
     */
    public void onCycleStarted(UUID allocationId) {
        afterCommit("cycle started " + allocationId, () -> markStarted(allocationId));
    }

    /** The machine finished its cycle: close its running allocation and advance the booking. */
    public void onMachineCycleFinished(UUID machineId) {
        afterCommit("cycle finished " + machineId, () -> markFinished(machineId));
    }

    /**
     * The customer took the laundry out of the locker (assisted service): closes the
     * booking through the same exactly-once path as a machine booking, with the email.
     */
    public void completeAfterPickup(UUID transactionId) {
        jdbc.query("SELECT id FROM scheduled_bookings WHERE transaction_id = ?",
                        (rs, row) -> rs.getObject(1, UUID.class), transactionId)
                .forEach(bookingId -> advanceBooking(bookingId, true));
    }

    private void markStarted(UUID allocationId) {
        int updated = jdbc.update("""
                UPDATE scheduled_bookings b SET status = 'IN_PROGRESS', updated_at = NOW()
                FROM booking_resource_allocations a
                WHERE a.id = ? AND b.id = a.booking_id
                  AND b.status IN ('CONFIRMED', 'CHECKED_IN', 'TRANSFER_PENDING')
                """, allocationId);
        if (updated > 0) {
            log.info("[BOOKING] allocation {} started → booking IN_PROGRESS", allocationId);
        }
    }

    private void markFinished(UUID machineId) {
        List<UUID> bookings = jdbc.query("""
                UPDATE booking_resource_allocations SET cycle_state = 'DONE'
                WHERE resource_id = ? AND cycle_state = 'RUNNING'
                RETURNING booking_id
                """, (rs, row) -> rs.getObject(1, UUID.class), machineId);
        bookings.stream().distinct().forEach(bookingId -> advanceBooking(bookingId, true));
    }

    /**
     * Safety net for lost COMPLETED messages: a RUNNING allocation whose
     * window ended more than the grace period ago, on a machine that is idle
     * again, is treated as finished. Machines in ERROR are left for staff.
     */
    @Scheduled(fixedDelayString = "${app.booking.completion-sweep-ms:60000}", initialDelay = 30_000)
    @Transactional
    public void sweepFinishedReservations() {
        if (!jobLock.tryLockForTransaction("booking-completion-sweep")) {
            return;
        }
        Timestamp cutoff = Timestamp.from(Instant.now().minus(Duration.ofMinutes(props.completionGraceMinutes())));
        List<UUID> bookings = jdbc.query("""
                UPDATE booking_resource_allocations a SET cycle_state = 'DONE'
                FROM machines m
                WHERE m.id = a.resource_id AND a.cycle_state = 'RUNNING'
                  AND a.occupied_until < ?
                  AND m.status IN ('AVAILABLE', 'RESERVED', 'COMPLETED')
                RETURNING a.booking_id
                """, (rs, row) -> rs.getObject(1, UUID.class), cutoff);
        if (!bookings.isEmpty()) {
            log.info("[BOOKING] sweep closed {} finished machine cycle(s)", bookings.size());
        }
        bookings.stream().distinct().forEach(bookingId -> advanceBooking(bookingId, true));

        // Bookings whose machine work is all done but that were never moved on
        // (e.g. finished before this lifecycle existed). Closed without email.
        List<UUID> stale = jdbc.query("""
                SELECT b.id FROM scheduled_bookings b
                WHERE b.status IN """ + OPEN_BOOKING_STATUSES + """

                  AND EXISTS (SELECT 1 FROM booking_resource_allocations a
                              WHERE a.booking_id = b.id AND a.cycle_state = 'DONE')
                  AND NOT EXISTS (SELECT 1 FROM booking_resource_allocations a
                                  WHERE a.booking_id = b.id AND a.cycle_state IN ('PENDING', 'RUNNING'))
                """, (rs, row) -> rs.getObject(1, UUID.class));
        stale.forEach(bookingId -> advanceBooking(bookingId, false));
    }

    private void afterCommit(String what, Runnable work) {
        Runnable inOwnTransaction = () -> {
            try {
                // REQUIRES_NEW is essential: inside afterCommit the caller's (already
                // committed) transaction is still bound, and REQUIRED would join it —
                // writes would bypass a real transaction and any afterCommit callback
                // registered by the work (the completion email) would never fire.
                org.springframework.transaction.support.TransactionTemplate template =
                        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                template.setPropagationBehavior(
                        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                template.executeWithoutResult(status -> work.run());
            } catch (RuntimeException e) {
                log.error("[BOOKING] lifecycle update failed ({}): {}", what, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    inOwnTransaction.run();
                }
            });
        } else {
            inOwnTransaction.run();
        }
    }

    private void advanceBooking(UUID bookingId, boolean notify) {
        Integer stillOpen = jdbc.queryForObject("""
                SELECT COUNT(*) FROM booking_resource_allocations
                WHERE booking_id = ? AND cycle_state IN ('PENDING', 'RUNNING')
                """, Integer.class, bookingId);
        if (stillOpen != null && stillOpen > 0) {
            // e.g. wash-dry: washer done, dryer still to go.
            jdbc.update("""
                    UPDATE scheduled_bookings SET status = 'TRANSFER_PENDING', updated_at = NOW()
                    WHERE id = ? AND status = 'IN_PROGRESS'
                    """, bookingId);
            return;
        }
        List<UUID> completed = jdbc.query("""
                UPDATE scheduled_bookings SET status = 'COMPLETED', updated_at = NOW()
                WHERE id = ? AND status IN """ + OPEN_BOOKING_STATUSES + """
                RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), bookingId);
        // The status guard makes this exactly-once: only the transition that
        // flipped the row sends the email.
        if (!completed.isEmpty()) {
            log.info("[BOOKING] booking {} COMPLETED (email={})", bookingId, notify);
            if (notify) sendCompletionEmailAfterCommit(bookingId);
        }
    }

    private void sendCompletionEmailAfterCommit(UUID bookingId) {
        Runnable send = () -> CompletableFuture.runAsync(() -> sendCompletionEmail(bookingId));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private void sendCompletionEmail(UUID bookingId) {
        try {
            List<LaundryCompletedEmail> mails = jdbc.query("""
                    SELECT u.email, u.full_name, d.name AS service_name, br.name AS branch_name,
                           br.address AS branch_address, b.start_time, b.updated_at, t.total_amount,
                           (SELECT string_agg(m.machine_code, ' + ' ORDER BY m.machine_code)
                              FROM booking_resource_allocations a JOIN machines m ON m.id = a.resource_id
                             WHERE a.booking_id = b.id) AS machine_codes
                    FROM scheduled_bookings b
                    JOIN transactions t ON t.id = b.transaction_id
                    JOIN users u ON u.id = t.customer_id
                    JOIN branches br ON br.id = b.branch_id
                    JOIN booking_service_definitions d ON d.code = b.service_code
                    WHERE b.id = ?
                    """, (rs, row) -> new LaundryCompletedEmail(
                    rs.getString("email"),
                    rs.getString("full_name"),
                    bookingId.toString().substring(0, 8).toUpperCase(),
                    rs.getString("service_name"),
                    rs.getString("branch_name"),
                    rs.getString("branch_address"),
                    rs.getString("machine_codes"),
                    toInstant(rs.getTimestamp("start_time")),
                    toInstant(rs.getTimestamp("updated_at")),
                    rs.getBigDecimal("total_amount") == null ? BigDecimal.ZERO : rs.getBigDecimal("total_amount")),
                    bookingId);
            if (mails.isEmpty() || mails.getFirst().toEmail() == null || mails.getFirst().toEmail().isBlank()) {
                log.warn("[BOOKING] no customer email for completed booking {}", bookingId);
                return;
            }
            emailService.sendLaundryCompletedEmail(mails.getFirst());
        } catch (RuntimeException e) {
            // Email is best effort: the booking is already COMPLETED.
            log.error("[BOOKING] completion email for {} failed: {}", bookingId, e.getMessage());
        }
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
