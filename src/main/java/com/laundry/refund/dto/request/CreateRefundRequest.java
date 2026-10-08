package com.laundry.refund.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for creating a refund request
 * SRS: FR-PAY-05 - Staff tạo Refund Request
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateRefundRequest {

    @NotNull(message = "Payment ID is required")
    private UUID paymentId;

    private UUID orderId;

    @NotNull(message = "Transaction ID is required")
    private UUID transactionId;

    @NotNull(message = "Branch ID is required")
    private UUID branchId;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;

    @NotBlank(message = "Reason is required")
    private String reason;

    private String evidence;  // Evidence description or attachment URLs
}
