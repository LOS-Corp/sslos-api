package com.laundry.refund.dto.response;

import com.laundry.refund.entity.RefundStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Response DTO for refund request
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundResponse {

    private UUID id;
    private UUID paymentId;
    private UUID orderId;
    private UUID transactionId;
    private UUID branchId;
    private UUID requestedBy;
    private String requestedByName;
    private UUID approvedBy;
    private String approvedByName;
    private BigDecimal amount;
    private String reason;
    private String evidence;
    private RefundStatus status;
    private String rejectionReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime completedAt;
}
