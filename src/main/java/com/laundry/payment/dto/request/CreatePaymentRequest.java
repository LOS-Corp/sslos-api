package com.laundry.payment.dto.request;

import com.laundry.payment.entity.PaymentMethod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request DTO for creating payment
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentRequest {

    @NotNull(message = "Transaction ID is required")
    private UUID transactionId;

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    private BigDecimal amount;

    @NotNull(message = "Payment method is required")
    private PaymentMethod paymentMethod;

    // For idempotency - prevents duplicate payment
    private String idempotencyKey;
}
