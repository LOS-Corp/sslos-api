package com.laundry.refund.entity;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An exception refund raised by Staff or the Owner with evidence and decided by an Owner who did
 * not raise it (BR-14, FR-REF-05). State changes go through {@link #approve} / {@link #reject} only.
 * Times are UTC wall-clock values (column type {@code timestamp}).
 */
@Entity
@Table(name = "refund_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "approved_by")
    private UUID decidedBy;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "evidence", columnDefinition = "TEXT")
    private String evidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "approved_at")
    private LocalDateTime decidedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** A new exception refund waiting for an Owner decision. */
    public static RefundRequest raise(UUID paymentId, UUID transactionId, UUID branchId, UUID requestedBy,
                                      BigDecimal amount, String reason, String evidence, LocalDateTime now) {
        RefundRequest request = new RefundRequest();
        request.paymentId = paymentId;
        request.transactionId = transactionId;
        request.branchId = branchId;
        request.requestedBy = requestedBy;
        request.amount = amount;
        request.reason = reason;
        request.evidence = evidence;
        request.status = RefundStatus.PENDING_APPROVAL;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    public void approve(UUID ownerId, LocalDateTime now) {
        requireDecidableBy(ownerId);
        status = RefundStatus.APPROVED;
        decide(ownerId, now);
    }

    public void reject(UUID ownerId, String why, LocalDateTime now) {
        requireDecidableBy(ownerId);
        status = RefundStatus.REJECTED;
        rejectionReason = why;
        decide(ownerId, now);
    }

    /** Money has reached the customer (wallet credit or proven bank transfer); APPROVED alone is not enough. */
    public void complete(LocalDateTime now) {
        if (status != RefundStatus.APPROVED) {
            throw BusinessException.of(ErrorCode.INVALID_STATE_TRANSITION, "Only an approved refund can be completed");
        }
        status = RefundStatus.COMPLETED;
        completedAt = now;
        updatedAt = now;
    }

    /** Requests that still count against the refund cap of the payment (BR-14). */
    public boolean countsTowardCap() {
        return status != RefundStatus.REJECTED && status != RefundStatus.FAILED;
    }

    private void requireDecidableBy(UUID ownerId) {
        if (status != RefundStatus.PENDING_APPROVAL) {
            throw BusinessException.of(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only a request waiting for approval can be decided (status " + status + ")");
        }
        if (requestedBy.equals(ownerId)) {
            throw BusinessException.of(ErrorCode.SELF_APPROVAL_FORBIDDEN,
                    "A refund request must be decided by an Owner who did not create it");
        }
    }

    private void decide(UUID ownerId, LocalDateTime now) {
        decidedBy = ownerId;
        decidedAt = now;
        updatedAt = now;
    }
}
