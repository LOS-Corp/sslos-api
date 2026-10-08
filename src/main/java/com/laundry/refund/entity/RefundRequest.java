package com.laundry.refund.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Refund Request entity
 * SRS: FR-PAY-05 - Staff tạo Refund Request gắn Payment/Order/Branch/Reason/Evidence
 * SRS: BR-RFD-001 - Chỉ Staff tạo; Manager đúng branch duyệt; Owner override/escalation
 * SRS: BR-RFD-002 - Refund luôn gắn branch/payment/order nguồn để đảm bảo traceability
 */
@Entity
@Table(name = "refund_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
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
    private UUID requestedBy;  // Staff who created the request

    @Column(name = "approved_by")
    private UUID approvedBy;   // Manager who approved

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "evidence", columnDefinition = "TEXT")
    private String evidence;  // Evidence description or attachment URLs

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private RefundStatus status = RefundStatus.CREATED;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "created_at")
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * SRS: BR-RFD-001 - Requester không được tự approve
     */
    public boolean canBeApprovedBy(UUID managerId) {
        return status == RefundStatus.PENDING_APPROVAL && !requestedBy.equals(managerId);
    }
}
