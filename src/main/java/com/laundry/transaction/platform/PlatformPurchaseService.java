package com.laundry.transaction.platform;

import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.dto.response.TransactionResponse;
import com.laundry.transaction.service.PurchaseService;

import java.math.BigDecimal;

/**
 * Platform-specific purchase service interface
 * Each platform implements its own specific business logic
 */
public interface PlatformPurchaseService {

    /**
     * Get the platform type
     */
    Platform getPlatform();

    /**
     * Validate purchase request for this platform
     */
    void validateRequest(CreatePurchaseRequest request);

    /**
     * Create purchase with platform-specific logic
     */
    TransactionResponse createPurchase(CreatePurchaseRequest request);

    /**
     * Get platform-specific payment method
     */
    com.laundry.payment.entity.PaymentMethod getDefaultPaymentMethod();

    /**
     * Handle platform-specific payment creation
     */
    default PaymentResponse createPayment(com.laundry.payment.service.PaymentService paymentService,
                                          java.util.UUID transactionId,
                                          BigDecimal amount) {
        var paymentRequest = new com.laundry.payment.dto.request.CreatePaymentRequest();
        paymentRequest.setTransactionId(transactionId);
        paymentRequest.setAmount(amount);
        paymentRequest.setPaymentMethod(getDefaultPaymentMethod());
        paymentRequest.setIdempotencyKey(getPlatform().name().toLowerCase() + "-" + transactionId + "-" + System.currentTimeMillis());
        return paymentService.createPayment(paymentRequest);
    }
}
