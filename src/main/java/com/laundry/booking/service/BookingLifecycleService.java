package com.laundry.booking.service;

import com.laundry.shared.EmailService;
import com.laundry.shared.LaundryCompletedEmail;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
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
 * COMPLETED or AVAILABLE. {@link #sweepFinishedReservations()} closes cycles
 * whose completion message was lost.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingLifecycleService {

    private static final String OPEN_BOOKING_STATUSES =
            "('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING')";

    private final JdbcTemplate jdbc;
    private final EmailService emailService;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** How long after a reservation's planned end the sweep waits before closing it. */
    @Value("${app.booking.completion-grace-minutes:15}")
    private long completionGraceMinutes;

    /**
     * A machine accepted START for {@code reservationId}: the customer's booking is now in use.
     * Runs after the caller's transaction commits, in its own transaction, so a
     * booking problem can never roll back the machine's own state change.
     */
    public void onCycleStarted(UUID reservationId) {
        afterCommit("cycle started " + reservationId, () -> markStarted(reservationId));
    }

    /** The machine finished its cycle: close its active reservation and advance the booking. */
    public void onMachineCycleFinished(UUID machineId) {
        afterCommit("cycle finished " + machineId, () -> markFinished(machineId));
    }

    private void markStarted(UUID reservationId) {
        int updated = jdbc.update("""
                UPDATE scheduled_bookings b SET status = 'IN_PROGRESS', updated_at = NOW()
                FROM machine_reservations mr
                WHERE mr.id = ? AND b.transaction_id = mr.transaction_id
                  AND b.status IN ('CONFIRMED', 'CHECKED_IN', 'TRANSFER_PENDING')
                """, reservationId);
        if (updated > 0) {
            log.info("[BOOKING] reservation {} started → booking IN_PROGRESS", reservationId);
        }
    }

    private void markFinished(UUID machineId) {
        List<UUID> transactions = jdbc.query("""
                UPDATE machine_reservations SET status = 'COMPLETED', updated_at = NOW()
                WHERE machine_id = ? AND status = 'ACTIVE'
                RETURNING transaction_id
                """, (rs, row) -> rs.getObject(1, UUID.class), machineId);
        transactions.stream().distinct().forEach(transactionId -> advanceBooking(transactionId, true));
    }

    /**
     * Safety net for lost COMPLETED messages: an ACTIVE reservation whose
     * window ended more than the grace period ago, on a machine that is idle
     * again, is treated as finished. Machines in ERROR are left for staff.
     */
    @Scheduled(fixedDelayString = "${app.booking.completion-sweep-ms:60000}", initialDelay = 30_000)
    @Transactional
    public void sweepFinishedReservations() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(completionGraceMinutes);
        List<UUID> transactions = jdbc.query("""
                UPDATE machine_reservations mr SET status = 'COMPLETED', updated_at = NOW()
                FROM machines m
                WHERE m.id = mr.machine_id AND mr.status = 'ACTIVE'
                  AND mr.end_time IS NOT NULL AND mr.end_time < ?
                  AND m.status IN ('AVAILABLE', 'RESERVED', 'COMPLETED')
                RETURNING mr.transaction_id
                """, (rs, row) -> rs.getObject(1, UUID.class), cutoff);
        if (!transactions.isEmpty()) {
            log.info("[BOOKING] sweep closed {} finished reservation(s)", transactions.size());
        }
        transactions.stream().distinct().forEach(transactionId -> advanceBooking(transactionId, true));

        // Bookings whose machine work is all done but that were never moved on
        // (e.g. finished before this lifecycle existed). Closed without email.
        List<UUID> stale = jdbc.query("""
                SELECT b.transaction_id FROM scheduled_bookings b
                WHERE b.status IN """ + OPEN_BOOKING_STATUSES + """

                  AND EXISTS (SELECT 1 FROM machine_reservations mr
                              WHERE mr.transaction_id = b.transaction_id AND mr.status = 'COMPLETED')
                  AND NOT EXISTS (SELECT 1 FROM machine_reservations mr
                                  WHERE mr.transaction_id = b.transaction_id
                                    AND mr.status IN ('RESERVED', 'CONFIRMED', 'ACTIVE'))
                """, (rs, row) -> rs.getObject(1, UUID.class));
        stale.forEach(transactionId -> advanceBooking(transactionId, false));
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

    private void advanceBooking(UUID transactionId, boolean notify) {
        Integer stillOpen = jdbc.queryForObject("""
                SELECT COUNT(*) FROM machine_reservations
                WHERE transaction_id = ? AND status IN ('RESERVED', 'CONFIRMED', 'ACTIVE')
                """, Integer.class, transactionId);
        if (stillOpen != null && stillOpen > 0) {
            // e.g. wash-dry: washer done, dryer still to go.
            jdbc.update("""
                    UPDATE scheduled_bookings SET status = 'TRANSFER_PENDING', updated_at = NOW()
                    WHERE transaction_id = ? AND status = 'IN_PROGRESS'
                    """, transactionId);
            return;
        }
        List<UUID> completed = jdbc.query("""
                UPDATE scheduled_bookings SET status = 'COMPLETED', updated_at = NOW()
                WHERE transaction_id = ? AND status IN """ + OPEN_BOOKING_STATUSES + """
                RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), transactionId);
        // The status guard makes this exactly-once: only the transition that
        // flipped the row sends the email.
        completed.forEach(bookingId -> {
            log.info("[BOOKING] booking {} COMPLETED (email={})", bookingId, notify);
            if (notify) sendCompletionEmailAfterCommit(bookingId);
        });
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
                              FROM machine_reservations mr JOIN machines m ON m.id = mr.machine_id
                             WHERE mr.transaction_id = b.transaction_id) AS machine_codes
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
