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
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A refund of one payment. An exception refund is raised by Staff or the Owner with evidence and decided by
 * an Owner who did not raise it (BR-14, FR-REF-05); its state changes go through {@link #approve} and
 * {@link #reject} only. A refund decided by a rule is {@link #settled} at once.
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
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "approved_at")
    private OffsetDateTime decidedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** A new exception refund waiting for an Owner decision. */
    public static RefundRequest raise(UUID paymentId, UUID transactionId, UUID requestedBy,
                                      BigDecimal amount, String reason, String evidence, OffsetDateTime now) {
        RefundRequest request = new RefundRequest();
        request.paymentId = paymentId;
        request.transactionId = transactionId;
        request.requestedBy = requestedBy;
        request.amount = amount;
        request.reason = reason;
        request.evidence = evidence;
        request.status = RefundStatus.PENDING_APPROVAL;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    /** A refund decided by a rule, already credited in this transaction (SM-06 R1–R2). */
    public static RefundRequest settled(UUID paymentId, UUID transactionId, UUID customerId,
                                        BigDecimal amount, String reason, OffsetDateTime now) {
        RefundRequest refund = raise(paymentId, transactionId, customerId, amount, reason, null, now);
        refund.status = RefundStatus.COMPLETED;
        refund.completedAt = now;
        return refund;
    }

    public void approve(UUID ownerId, OffsetDateTime now) {
        requireDecidableBy(ownerId);
        status = RefundStatus.APPROVED;
        decide(ownerId, now);
    }

    public void reject(UUID ownerId, String why, OffsetDateTime now) {
        requireDecidableBy(ownerId);
        status = RefundStatus.REJECTED;
        rejectionReason = why;
        decide(ownerId, now);
    }

    /** Money has reached the customer (wallet credit or proven bank transfer); APPROVED alone is not enough. */
    public void complete(OffsetDateTime now) {
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

    private void decide(UUID ownerId, OffsetDateTime now) {
        decidedBy = ownerId;
        decidedAt = now;
        updatedAt = now;
    }
}
