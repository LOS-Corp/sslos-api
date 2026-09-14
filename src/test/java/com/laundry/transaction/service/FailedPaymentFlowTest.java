package com.laundry.transaction.service;

import com.laundry.payment.dto.request.CreatePaymentRequest;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.PaymentMethod;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.service.PaymentService;
import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.dto.response.TransactionResponse;
import com.laundry.transaction.entity.ServiceType;
import com.laundry.transaction.entity.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for failed payment flow
 */
@SpringBootTest
@ActiveProfiles("test")
class FailedPaymentFlowTest {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private PaymentService paymentService;

    @Test
    void testFailedPaymentReleasesResource() {
        UUID customerId = UUID.randomUUID();
        UUID machineId = UUID.randomUUID();

        // Create purchase
        CreatePurchaseRequest request = CreatePurchaseRequest.builder()
            .customerId(customerId)
            .serviceType(ServiceType.SELF_SERVICE)
            .machineId(machineId)
            .build();

        TransactionResponse transaction = purchaseService.createPurchase(request);
        assertEquals(TransactionStatus.PENDING_PAYMENT, transaction.getStatus());

        // Create payment
        CreatePaymentRequest paymentRequest = CreatePaymentRequest.builder()
            .transactionId(transaction.getTransactionId())
            .amount(transaction.getTotalAmount())
            .paymentMethod(PaymentMethod.ONLINE)
            .build();

        PaymentResponse payment = paymentService.createPayment(paymentRequest);

        // Simulate failed callback
        var callbackRequest = new com.laundry.payment.dto.request.PaymentCallbackRequest();
        callbackRequest.setTransactionId(payment.getGatewayTransactionId());
        callbackRequest.setStatus("FAILED");
        callbackRequest.setMessage("Payment declined");

        PaymentResponse failedPayment = paymentService.processCallback(payment.getPaymentId(), callbackRequest);

        // Verify payment failed
        assertEquals(PaymentStatus.FAILED, failedPayment.getStatus());

        // Verify transaction cancelled
        TransactionResponse cancelledTransaction = purchaseService.getTransaction(transaction.getTransactionId());
        assertEquals(TransactionStatus.CANCELLED, cancelledTransaction.getStatus());
    }
}
