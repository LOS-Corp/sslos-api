package com.laundry.payment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Mock payment gateway for development/testing
 * In production, this would be replaced with VNPay, MoMo, etc.
 */
@Component
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "mock", matchIfMissing = true)
@Slf4j
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public PaymentResult createPayment(UUID paymentId, BigDecimal amount, String returnUrl) {
        // Simulate payment gateway - return mock URL
        String mockTransactionRef = "MOCK_" + UUID.randomUUID().toString().substring(0, 8);
        String mockPaymentUrl = "https://mock-payment-gateway.com/pay?" +
                               "paymentId=" + paymentId + 
                               "&amount=" + amount +
                               "&ref=" + mockTransactionRef +
                               "&returnUrl=" + returnUrl;
        
        log.info("Mock payment created: paymentId={}, amount={}, ref={}", paymentId, amount, mockTransactionRef);
        
        return PaymentResult.success(mockPaymentUrl, mockTransactionRef);
    }

    @Override
    public boolean verifyCallback(String transactionId, String signature) {
        // Mock always returns true for development
        log.info("Mock callback verified: transactionId={}", transactionId);
        return true;
    }

    @Override
    public String getGatewayName() {
        return "MOCK";
    }
}
