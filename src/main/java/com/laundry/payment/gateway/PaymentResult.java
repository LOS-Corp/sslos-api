package com.laundry.payment.gateway;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result from payment gateway
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResult {

    private boolean success;
    private String paymentUrl;
    private String qrCode;
    private String transactionReference;
    private String errorMessage;

    public static PaymentResult success(String paymentUrl, String transactionReference) {
        return PaymentResult.builder()
            .success(true)
            .paymentUrl(paymentUrl)
            .transactionReference(transactionReference)
            .build();
    }

    public static PaymentResult success(String qrCode, String transactionReference, boolean isQrCode) {
        return PaymentResult.builder()
            .success(true)
            .qrCode(qrCode)
            .transactionReference(transactionReference)
            .build();
    }

    public static PaymentResult failure(String errorMessage) {
        return PaymentResult.builder()
            .success(false)
            .errorMessage(errorMessage)
            .build();
    }
}
