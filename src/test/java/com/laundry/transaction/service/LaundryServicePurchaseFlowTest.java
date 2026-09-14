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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Laundry Service purchase flow
 */
@SpringBootTest
@ActiveProfiles("test")
class LaundryServicePurchaseFlowTest {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private PaymentService paymentService;

    private UUID customerId;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
    }

    @Test
    void testLaundryServicePurchaseFlow() {
        // Step 1: Create purchase transaction
        CreatePurchaseRequest request = CreatePurchaseRequest.builder()
            .customerId(customerId)
            .serviceType(ServiceType.LAUNDRY_SERVICE)
            .customerName("Test Customer")
            .customerPhone("0123456789")
            .serviceNotes("Wash delicate")
            .estimatedWeight(new java.math.BigDecimal("2.5"))
            .build();

        TransactionResponse transaction = purchaseService.createPurchase(request);

        // Verify transaction created
        assertNotNull(transaction.getTransactionId());
        assertEquals(TransactionStatus.PENDING_PAYMENT, transaction.getStatus());
        assertEquals(ServiceType.LAUNDRY_SERVICE, transaction.getServiceType());
        assertNotNull(transaction.getLaundryOrder());
        assertEquals("Test Customer", transaction.getLaundryOrder().getCustomerName());
        assertEquals("0123456789", transaction.getLaundryOrder().getCustomerPhone());

        // Step 2: Create and confirm payment
        CreatePaymentRequest paymentRequest = CreatePaymentRequest.builder()
            .transactionId(transaction.getTransactionId())
            .amount(transaction.getTotalAmount())
            .paymentMethod(PaymentMethod.ONLINE)
            .idempotencyKey("laundry-test-" + UUID.randomUUID())
            .build();

        PaymentResponse payment = paymentService.createPayment(paymentRequest);

        // Simulate callback
        var callbackRequest = new com.laundry.payment.dto.request.PaymentCallbackRequest();
        callbackRequest.setTransactionId(payment.getGatewayTransactionId());
        callbackRequest.setStatus("SUCCESS");
        callbackRequest.setGatewayTransactionId(payment.getGatewayTransactionId());
        callbackRequest.setSignature("mock-signature");

        PaymentResponse confirmedPayment = paymentService.processCallback(payment.getPaymentId(), callbackRequest);

        assertEquals(PaymentStatus.PAID, confirmedPayment.getStatus());

        // Verify transaction and order confirmed
        TransactionResponse confirmedTransaction = purchaseService.getTransaction(transaction.getTransactionId());
        assertEquals(TransactionStatus.PAID, confirmedTransaction.getStatus());
        assertNotNull(confirmedTransaction.getLaundryOrder());
    }

    @Test
    void testPurchaseWithoutCustomerName() {
        CreatePurchaseRequest request = CreatePurchaseRequest.builder()
            .customerId(customerId)
            .serviceType(ServiceType.LAUNDRY_SERVICE)
            // Missing customerName
            .build();

        assertThrows(com.laundry.transaction.exception.TransactionException.class, () -> {
            purchaseService.createPurchase(request);
        });
    }
}
