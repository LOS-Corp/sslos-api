package com.laundry.booking.service;

import com.laundry.audit.service.AuditAction;
import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditEntry;
import com.laundry.booking.event.BookingReleased;
import com.laundry.config.BookingProperties;
import com.laundry.refund.domain.RefundPolicy;
import com.laundry.refund.domain.RefundPolicy.CancellationCause;
import com.laundry.refund.domain.RefundPolicy.RefundDecision;
import com.laundry.refund.repository.PolicyViolationRepository;
import com.laundry.refund.service.AutomaticRefundService;
import com.laundry.shared.money.Money;
import com.laundry.wallet.entity.WalletEntryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BR-09 / SM-01 B12: the washer of a Wash–Dry order finished and the dryer was not started in time. The dryer
 * interval is released, the booking completes, and the dryer stage alone is refunded: by the BR-12 rate with a
 * NO_SHOW violation, or 100 % with none when the dryer itself was unavailable (BR-13).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferExpiryJob {

    private static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final PolicyViolationRepository violations;
    private final AutomaticRefundService refunds;
    private final ComboStageValues stageValues;
    private final BookingLifecycleService lifecycle;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final BookingProperties props;

    @Scheduled(fixedDelayString = "${app.booking.transfer-sweep-ms:60000}", initialDelay = 50_000)
    @Transactional
    public void sweep() {
        Instant now = clock.instant();
        List<Expired> expired = expired(now);
        expired.forEach(booking -> close(booking, now));
        if (!expired.isEmpty()) {
            log.info("Transfer sweep: {} Wash–Dry booking(s) closed without the dryer", expired.size());
        }
    }

    private void close(Expired booking, Instant now) {
        CancellationCause cause = dryerUnavailable(booking.dryerId())
                ? CancellationCause.SYSTEM_FAILURE : CancellationCause.NO_SHOW;
        Money stage = stageValues.dryerStage(booking.total());
        Money left = refunds.refundable(booking.transactionId());
        Money eligible = left.compareTo(stage) < 0 ? left : stage;
        RefundDecision decision = new RefundPolicy(props.freeCancellationBefore()).evaluate(cause, now, booking.start(),
                violations.countInWindow(booking.customerId(), now.minus(RefundPolicy.VIOLATION_WINDOW), now), eligible);

        lifecycle.completeWithoutDryer(booking.bookingId());
        events.publishEvent(new BookingReleased(booking.bookingId(), "DRYER_NOT_STARTED"));
        if (decision.recordsViolation()) {
            violations.record(booking.customerId(), booking.bookingId(), decision.violation(), now);
        }
        Money credited = decision.amount().isPositive()
                ? refunds.settle(booking.transactionId(), booking.customerId(), decision.amount(),
                        WalletEntryType.REFUND_CREDIT, "Hoàn tiền giai đoạn sấy không sử dụng (" + decision.ratePercent() + "%)")
                : Money.ZERO;
        audit.record(AuditEntry.of(AuditAction.BOOKING_NO_SHOW, "BOOKING", booking.bookingId(), null)
                .withChange(Map.of("status", "TRANSFER_PENDING"), Map.of("status", "COMPLETED", "stage", "DRYER",
                        "cause", cause.name(), "refundVnd", credited.vnd())));
    }

    /** The later of the planned dryer start and the washer's actual end plus the transfer time. */
    private List<Expired> expired(Instant now) {
        return jdbc.query("""
                SELECT sb.id, sb.start_time, t.id transaction_id, t.customer_id, t.total_amount,
                       a.resource_id dryer_id
                FROM scheduled_bookings sb
                JOIN transactions t ON t.id = sb.transaction_id
                JOIN booking_service_definitions d ON d.code = sb.service_code
                JOIN booking_resource_allocations a ON a.booking_id = sb.id AND a.cycle_state = 'PENDING'
                WHERE sb.status = 'TRANSFER_PENDING' AND t.customer_id IS NOT NULL
                  AND GREATEST(a.occupied_from, sb.updated_at + make_interval(mins => d.transfer_minutes)) < ?
                  AND NOT EXISTS (
                      SELECT 1 FROM machine_start_authorizations msa
                      WHERE msa.allocation_id = a.id AND msa.status IN ('GRANTED', 'START_PENDING'))
                ORDER BY sb.start_time
                LIMIT ?
                FOR UPDATE OF sb SKIP LOCKED
                """, (rs, row) -> new Expired(rs.getObject("id", UUID.class), rs.getObject("transaction_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("dryer_id", UUID.class), rs.getTimestamp("start_time").toInstant(),
                Money.of(rs.getBigDecimal("total_amount"))), Timestamp.from(now), BATCH);
    }

    private boolean dryerUnavailable(UUID dryerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT NOT online OR status IN ('ERROR', 'OUT_OF_SERVICE', 'OFFLINE') FROM machines WHERE id = ?
                """, Boolean.class, dryerId));
    }

    private record Expired(UUID bookingId, UUID transactionId, UUID customerId, UUID dryerId,
                           Instant start, Money total) {
    }
}
