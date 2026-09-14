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

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Self-Service purchase flow
 */
@SpringBootTest
@ActiveProfiles("test")
class SelfServicePurchaseFlowTest {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private PaymentService paymentService;

    private UUID customerId;
    private UUID machineId;

    @BeforeEach
    void setUp() {
        customerId = UUID.randomUUID();
        machineId = UUID.randomUUID();
    }

    @Test
    void testSelfServicePurchaseFlow() {
        // Step 1: Create purchase transaction
        CreatePurchaseRequest request = CreatePurchaseRequest.builder()
            .customerId(customerId)
            .serviceType(ServiceType.SELF_SERVICE)
            .machineId(machineId)
            .build();

        TransactionResponse transaction = purchaseService.createPurchase(request);

        // Verify transaction created
        assertNotNull(transaction.getTransactionId());
        assertEquals(TransactionStatus.PENDING_PAYMENT, transaction.getStatus());
        assertEquals(ServiceType.SELF_SERVICE, transaction.getServiceType());
        assertNotNull(transaction.getMachineReservation());
        assertEquals(machineId, transaction.getMachineReservation().getMachineId());

        // Step 2: Create payment
        CreatePaymentRequest paymentRequest = CreatePaymentRequest.builder()
            .transactionId(transaction.getTransactionId())
            .amount(transaction.getTotalAmount())
            .paymentMethod(PaymentMethod.ONLINE)
            .idempotencyKey("test-" + UUID.randomUUID())
            .build();

        PaymentResponse payment = paymentService.createPayment(paymentRequest);

        // Verify payment created
        assertNotNull(payment.getPaymentId());
        assertEquals(PaymentStatus.PENDING, payment.getStatus());
        assertNotNull(payment.getPaymentUrl()); // Mock gateway returns URL

        // Step 3: Simulate callback - payment success
        var callbackRequest = new com.laundry.payment.dto.request.PaymentCallbackRequest();
        callbackRequest.setTransactionId(payment.getGatewayTransactionId());
        callbackRequest.setStatus("SUCCESS");
        callbackRequest.setGatewayTransactionId(payment.getGatewayTransactionId());
        callbackRequest.setSignature("mock-signature");

        PaymentResponse confirmedPayment = paymentService.processCallback(payment.getPaymentId(), callbackRequest);

        // Verify payment confirmed
        assertEquals(PaymentStatus.PAID, confirmedPayment.getStatus());
        assertNotNull(confirmedPayment.getPaidAt());

        // Step 4: Verify transaction confirmed
        TransactionResponse confirmedTransaction = purchaseService.getTransaction(transaction.getTransactionId());
        assertEquals(TransactionStatus.PAID, confirmedTransaction.getStatus());

        // Step 5: Verify machine reservation confirmed
        assertNotNull(confirmedTransaction.getMachineReservation());
        assertEquals("CONFIRMED", confirmedTransaction.getMachineReservation().getStatus().name());
    }

    @Test
    void testPurchaseWithoutPaymentNotConfirmed() {
        CreatePurchaseRequest request = CreatePurchaseRequest.builder()
            .customerId(customerId)
            .serviceType(ServiceType.SELF_SERVICE)
            .machineId(machineId)
            .build();

        TransactionResponse transaction = purchaseService.createPurchase(request);

        // Transaction should be PENDING_PAYMENT
        assertEquals(TransactionStatus.PENDING_PAYMENT, transaction.getStatus());
        assertNotNull(transaction.getMachineReservation());
        assertEquals("RESERVED", transaction.getMachineReservation().getStatus().name());
    }

    @Test
    void testPurchaseWithInvalidMachineId() {
        CreatePurchaseRequest request = CreatePurchaseRequest.builder()
            .customerId(customerId)
            .serviceType(ServiceType.SELF_SERVICE)
            .machineId(null) // Missing machine ID
            .build();

        assertThrows(com.laundry.transaction.exception.TransactionException.class, () -> {
            purchaseService.createPurchase(request);
        });
    }
}
