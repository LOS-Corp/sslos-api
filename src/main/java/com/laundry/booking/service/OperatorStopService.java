package com.laundry.booking.service;

import com.laundry.audit.service.AuditAction;
import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditEntry;
import com.laundry.refund.service.AutomaticRefundService;
import com.laundry.shared.money.Money;
import com.laundry.wallet.entity.WalletEntryType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Staff, an Owner or an Admin stopped a customer's running cycle (FR-ACT-11, FR-REF-07, BR-13). It is not the
 * customer's doing: the booking is cancelled with no violation and the unused part is refunded 100 %. A stage
 * that already finished stays paid (BR-10). The firmware reports a stopped cycle as AVAILABLE, the same as a
 * cycle whose COMPLETED message was lost, so only the recent command tells them apart.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class OperatorStopService {

    private final JdbcTemplate jdbc;
    private final AutomaticRefundService refunds;
    private final ComboStageValues stageValues;
    private final AuditService audit;

    /** The running cycle on this machine, if someone other than its customer has just stopped it. */
    Optional<StoppedCycle> stoppedCycle(UUID machineId) {
        List<StoppedCycle> cycles = jdbc.query("""
                SELECT a.id, a.booking_id, t.id transaction_id, t.customer_id, t.total_amount,
                       EXISTS (SELECT 1 FROM booking_resource_allocations done
                               WHERE done.booking_id = a.booking_id AND done.cycle_state = 'DONE') earlier_stage_done
                FROM booking_resource_allocations a
                JOIN scheduled_bookings b ON b.id = a.booking_id
                JOIN transactions t ON t.id = b.transaction_id
                WHERE a.resource_id = ? AND a.cycle_state = 'RUNNING' AND t.customer_id IS NOT NULL
                  AND EXISTS (
                      SELECT 1 FROM device_commands c
                      WHERE c.machine_id = a.resource_id AND c.command IN ('STOP', 'RESET')
                        AND c.status IN ('SENT', 'ACKNOWLEDGED')
                        AND c.created_at > NOW() - interval '2 minutes'
                        AND c.issued_by_user_id IS DISTINCT FROM t.customer_id)
                """, (rs, row) -> new StoppedCycle(rs.getObject("id", UUID.class), rs.getObject("booking_id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("customer_id", UUID.class), Money.of(rs.getBigDecimal("total_amount")),
                rs.getBoolean("earlier_stage_done")), machineId);
        return cycles.stream().findFirst();
    }

    @Transactional
    void settle(StoppedCycle cycle) {
        jdbc.update("UPDATE booking_resource_allocations SET cycle_state = 'DONE' WHERE id = ?", cycle.allocationId());
        int cancelled = jdbc.update("""
                UPDATE scheduled_bookings SET status = 'CANCELLED', updated_at = NOW(),
                    reconciliation_reason = 'Stopped by an operator mid-cycle (BR-13)'
                WHERE id = ? AND status = 'IN_PROGRESS'
                """, cycle.bookingId());
        if (cancelled == 0) {
            return;
        }
        Money left = refunds.refundable(cycle.transactionId());
        Money unused = left;
        if (cycle.earlierStageDone()) {
            Money stage = stageValues.dryerStage(cycle.total());
            unused = left.compareTo(stage) < 0 ? left : stage;
        }
        Money credited = unused.isPositive()
                ? refunds.settle(cycle.transactionId(), cycle.customerId(), unused,
                        WalletEntryType.REFUND_CREDIT, "Hoàn tiền do cửa hàng dừng máy giữa chu trình")
                : Money.ZERO;
        audit.record(AuditEntry.of(AuditAction.BOOKING_STOPPED_BY_OPERATOR, "BOOKING", cycle.bookingId(), null)
                .withChange(Map.of("status", "IN_PROGRESS"), Map.of("status", "CANCELLED", "refundVnd", credited.vnd())));
        log.info("[BOOKING] booking {} cancelled: stopped by an operator, {} VND refunded", cycle.bookingId(), credited.vnd());
    }

    record StoppedCycle(UUID allocationId, UUID bookingId, UUID transactionId, UUID customerId,
                        Money total, boolean earlierStageDone) {
    }
}
