package com.laundry.booking.service;

import com.laundry.audit.service.AuditAction;
import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditEntry;
import com.laundry.refund.service.AutomaticRefundService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.money.Money;
import com.laundry.shared.security.CurrentUser;
import com.laundry.wallet.entity.WalletEntryType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PAYMENT_REVIEW_REQUIRED (BR-04): money arrived for a booking that can no longer take it. The money is
 * credited when it arrives; the Owner reviews the case and closes it with a reason. Closing credits anything
 * still owed, so a booking from before the credit rule cannot end with the customer's money held.
 */
@Service
@RequiredArgsConstructor
public class PaymentReviewService {

    private final JdbcTemplate jdbc;
    private final AutomaticRefundService refunds;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<PaymentReview> open() {
        // Rows first, then the refundable amounts: no nested query while the ResultSet is open.
        record Row(UUID id, UUID branchId, UUID transactionId, UUID customerId, BigDecimal total, String reason,
                   Instant since) {
        }
        List<Row> rows = jdbc.query("""
                SELECT sb.id, sb.branch_id, sb.transaction_id, sb.reconciliation_reason, sb.updated_at,
                       t.customer_id, t.total_amount
                FROM scheduled_bookings sb JOIN transactions t ON t.id = sb.transaction_id
                WHERE sb.status = 'PAYMENT_REVIEW_REQUIRED'
                ORDER BY sb.updated_at
                """, (rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getBigDecimal("total_amount"), rs.getString("reconciliation_reason"),
                rs.getTimestamp("updated_at").toInstant()));
        return rows.stream().map(r -> new PaymentReview(r.id(), r.branchId(), r.customerId(), r.total(),
                refunds.refundable(r.transactionId()).vnd(), r.reason(), r.since())).toList();
    }

    @Transactional
    public void resolve(UUID bookingId, String reason, CurrentUser owner) {
        if (reason == null || reason.isBlank()) {
            throw BusinessException.of(ErrorCode.REASON_REQUIRED, "Please enter a reason for this action");
        }
        List<Open> rows = jdbc.query("""
                SELECT sb.branch_id, sb.transaction_id, t.customer_id
                FROM scheduled_bookings sb JOIN transactions t ON t.id = sb.transaction_id
                WHERE sb.id = ? AND sb.status = 'PAYMENT_REVIEW_REQUIRED'
                FOR UPDATE OF sb
                """, (rs, row) -> new Open(rs.getObject("branch_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), rs.getObject("customer_id", UUID.class)), bookingId);
        if (rows.isEmpty()) {
            throw BusinessException.of(ErrorCode.INVALID_STATE_TRANSITION, "This booking is not waiting for a payment review");
        }
        Open booking = rows.getFirst();
        Money owed = refunds.refundable(booking.transactionId());
        Money credited = booking.customerId() != null && owed.isPositive()
                ? refunds.settle(booking.transactionId(), booking.branchId(), booking.customerId(), owed,
                        WalletEntryType.LATE_PAYMENT_CREDIT, "Chủ cửa hàng hoàn thanh toán đến sau khi đơn đã đóng")
                : Money.ZERO;
        jdbc.update("""
                UPDATE scheduled_bookings SET status = 'CANCELLED', updated_at = NOW(), reconciliation_reason = ?
                WHERE id = ?
                """, reason.trim(), bookingId);
        audit.record(AuditEntry.of(AuditAction.BOOKING_PAYMENT_REVIEW, "BOOKING", bookingId, booking.branchId(), owner)
                .withReason(reason.trim())
                .withChange(Map.of("status", "PAYMENT_REVIEW_REQUIRED"),
                        Map.of("status", "CANCELLED", "refundVnd", credited.vnd())));
    }

    private record Open(UUID branchId, UUID transactionId, UUID customerId) {
    }

    /** {@code stillOwedVnd} is what closing the review would credit; zero once the wallet has it. */
    public record PaymentReview(UUID bookingId, UUID branchId, UUID customerId, BigDecimal totalAmount,
                                long stillOwedVnd, String reason, Instant since) {
    }
}
