package com.laundry.booking.service;

import com.laundry.audit.service.AuditAction;
import com.laundry.config.BookingProperties;
import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditEntry;
import com.laundry.machine.domain.CheckInWindowPolicy;
import com.laundry.refund.domain.RefundPolicy;
import com.laundry.refund.domain.RefundPolicy.CancellationCause;
import com.laundry.refund.domain.RefundPolicy.RefundDecision;
import com.laundry.refund.repository.PolicyViolationRepository;
import com.laundry.shared.money.Money;
import com.laundry.shared.scheduling.JobLock;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * UC-28 step 3: a confirmed booking whose window closed with nobody showing up becomes NO_SHOW.
 * <ul>
 *   <li>Self-service: the check-in window (BR-07) closed without a START.</li>
 *   <li>Assisted laundry: the drop-off window (BR-17, T+60) closed with the laundry never put in the
 *       locker — a drop-off moves the booking to CHECKED_IN, so it is no longer a candidate.</li>
 * </ul>
 * The customer gets the BR-12 rate of the paid amount back in the wallet and one violation is
 * recorded — unless a booked machine or the locker cabinet was offline or broken, which is a system
 * failure: 100 % back and no violation (BR-13). The job is idempotent and safe on several instances.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NoShowJob {

    private static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final JobLock jobLock;
    private final CheckInWindowPolicy checkInWindow;
    private final PolicyViolationRepository violations;
    private final WalletService wallet;
    private final AuditService audit;
    private final Clock clock;
    private final BookingProperties props;


    @Scheduled(fixedDelayString = "${app.booking.no-show-sweep-ms:60000}", initialDelay = 45_000)
    @Transactional
    public void sweep() {
        if (!jobLock.tryLockForTransaction("booking-no-show")) {
            return;
        }
        Instant now = clock.instant();
        int marked = 0;
        for (Candidate candidate : candidates(now)) {
            if (windowClosesAt(candidate).isBefore(now)) {
                markNoShow(candidate, now);
                marked++;
            }
        }
        if (marked > 0) {
            log.info("No-show sweep: {} booking(s) marked NO_SHOW", marked);
        }
    }

    private Instant windowClosesAt(Candidate booking) {
        return booking.assisted()
                ? booking.start().plus(props.assistedDropOffWindow())
                : checkInWindow.windowFor(booking.start(), booking.paidAt()).closesAt();
    }

    private void markNoShow(Candidate booking, Instant now) {
        CancellationCause cause = machineFailed(booking.bookingId())
                ? CancellationCause.SYSTEM_FAILURE : CancellationCause.NO_SHOW;
        RefundDecision decision = booking.customerId() == null
                ? null
                : new RefundPolicy(props.freeCancellationBefore()).evaluate(cause, now, booking.start(),
                        violations.countInWindow(booking.customerId(), now.minus(RefundPolicy.VIOLATION_WINDOW), now),
                        booking.paid());

        jdbc.update("""
                UPDATE scheduled_bookings SET status = 'NO_SHOW', updated_at = NOW(), reconciliation_reason = ?
                WHERE id = ? AND status = 'CONFIRMED'
                """, reason(booking, cause), booking.bookingId());

        if (decision != null) {
            if (decision.recordsViolation()) {
                violations.record(booking.customerId(), booking.bookingId(), decision.violation(), now);
            }
            if (decision.amount().isPositive()) {
                wallet.credit(booking.customerId(), decision.amount(), WalletEntryType.REFUND_CREDIT,
                        "no-show:" + booking.bookingId(), "Hoàn tiền không đến sử dụng (" + decision.ratePercent() + "%)");
            }
        }
        audit.record(AuditEntry.of(AuditAction.BOOKING_NO_SHOW, "BOOKING", booking.bookingId(), booking.branchId(), null)
                .withChange(Map.of("status", "CONFIRMED"), Map.of("status", "NO_SHOW", "cause", cause.name(),
                        "refundVnd", decision == null ? 0 : decision.amount().vnd())));
    }

    private static String reason(Candidate booking, CancellationCause cause) {
        if (cause == CancellationCause.SYSTEM_FAILURE) {
            return booking.assisted()
                    ? "Drop-off window closed while the locker cabinet was unavailable (BR-13)"
                    : "Check-in window closed while a booked machine was unavailable (BR-13)";
        }
        return booking.assisted()
                ? "Drop-off window closed without the laundry in the locker (BR-17)"
                : "Check-in window closed without START (BR-07)";
    }

    /**
     * Confirmed bookings past the earliest point their window can close: self-service once T + check-in
     * close has passed (use-now orders close at PAID + 10, checked in Java), assisted at T + drop-off window.
     */
    private List<Candidate> candidates(Instant now) {
        return jdbc.query("""
                SELECT sb.id, sb.branch_id, sb.start_time, t.id transaction_id, t.customer_id, t.total_amount, t.paid_at,
                       d.service_mode = 'ASSISTED_LOCKER' AS assisted
                FROM scheduled_bookings sb
                JOIN transactions t ON t.id = sb.transaction_id
                JOIN booking_service_definitions d ON d.code = sb.service_code
                WHERE sb.status = 'CONFIRMED'
                  AND ((d.service_mode = 'SELF_SERVICE' AND sb.start_time < ?)
                    OR (d.service_mode = 'ASSISTED_LOCKER' AND sb.start_time < ?))
                  AND NOT EXISTS (
                      SELECT 1 FROM machine_start_authorizations msa
                      WHERE msa.booking_id = sb.id AND msa.status IN ('GRANTED', 'START_PENDING', 'CONSUMED'))
                ORDER BY sb.start_time
                LIMIT ?
                FOR UPDATE OF sb SKIP LOCKED
                """, (rs, row) -> {
            LocalDateTime paidAt = rs.getObject("paid_at", LocalDateTime.class);
            BigDecimal amount = rs.getBigDecimal("total_amount");
            return new Candidate(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                    rs.getObject("transaction_id", UUID.class), rs.getObject("customer_id", UUID.class),
                    rs.getTimestamp("start_time").toInstant(),
                    paidAt == null ? null : paidAt.toInstant(ZoneOffset.UTC),
                    Money.of(amount), rs.getBoolean("assisted"));
        }, Timestamp.from(now.minus(props.checkInClosesAfter())),
                Timestamp.from(now.minus(props.assistedDropOffWindow())), BATCH);
    }

    /**
     * BR-13: a booked machine — or, for assisted laundry, the cabinet of the booked compartment — that is
     * offline or out of order makes the no-show the system's fault.
     */
    private boolean machineFailed(UUID bookingId) {
        Boolean failed = jdbc.queryForObject("""
                SELECT COALESCE(bool_or(NOT m.online OR m.status IN ('ERROR', 'OUT_OF_SERVICE', 'OFFLINE')), FALSE)
                FROM booking_resource_allocations a
                LEFT JOIN branch_service_resources r ON r.id = a.resource_id
                JOIN machines m ON m.id = COALESCE(r.locker_machine_id, a.resource_id)
                WHERE a.booking_id = ?
                """, Boolean.class, bookingId);
        return Boolean.TRUE.equals(failed);
    }

    private record Candidate(UUID bookingId, UUID branchId, UUID transactionId, UUID customerId, Instant start,
                             Instant paidAt, Money paid, boolean assisted) {
    }
}
