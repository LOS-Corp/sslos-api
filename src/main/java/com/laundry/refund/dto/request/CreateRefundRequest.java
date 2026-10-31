package com.laundry.refund.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Exception refund raised by Staff or the Owner. The transaction and branch are derived from the
 * payment, and the requester from the token — the body cannot choose them (BR-01).
 */
public record CreateRefundRequest(
        @NotNull UUID paymentId,
        @NotNull @DecimalMin(value = "1") @Digits(integer = 8, fraction = 0) BigDecimal amount,
        @NotBlank @Size(max = 1000) String reason,
        @Size(max = 2000) String evidence) {
}
