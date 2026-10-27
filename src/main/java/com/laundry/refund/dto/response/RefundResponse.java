package com.laundry.refund.dto.response;

import com.laundry.refund.entity.RefundRequest;
import com.laundry.refund.entity.RefundStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID paymentId,
        UUID orderId,
        UUID transactionId,
        UUID branchId,
        UUID requestedBy,
        UUID approvedBy,
        BigDecimal amount,
        String reason,
        String evidence,
        RefundStatus status,
        String rejectionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime approvedAt,
        LocalDateTime completedAt) {

    public static RefundResponse from(RefundRequest r) {
        return new RefundResponse(r.getId(), r.getPaymentId(), r.getOrderId(), r.getTransactionId(),
                r.getBranchId(), r.getRequestedBy(), r.getDecidedBy(), r.getAmount(), r.getReason(),
                r.getEvidence(), r.getStatus(), r.getRejectionReason(), r.getCreatedAt(), r.getUpdatedAt(),
                r.getDecidedAt(), r.getCompletedAt());
    }
}
