package com.laundry.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payment Gateway abstraction
 * This interface allows implementing different payment providers (VNPay, MoMo, etc.)
 */
public interface PaymentGateway {

    /**
     * Create a payment request and return the payment URL or QR code
     */
    PaymentResult createPayment(UUID paymentId, BigDecimal amount, String returnUrl);

    /**
     * Verify a callback from the payment gateway
     */
    boolean verifyCallback(String transactionId, String signature);

    /**
     * Get the gateway name
     */
    String getGatewayName();
}
