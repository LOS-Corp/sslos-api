package com.laundry.refund.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for approving/rejecting a refund request
 * SRS: FR-PAY-06 - Manager của đúng branch Approve/Reject
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApproveRefundRequest {

    @NotNull(message = "Manager ID is required")
    private UUID managerId;

    @NotNull(message = "Manager branch ID is required")
    private UUID managerBranchId;

    @Size(max = 500, message = "Rejection reason must not exceed 500 characters")
    private String rejectionReason;
}
