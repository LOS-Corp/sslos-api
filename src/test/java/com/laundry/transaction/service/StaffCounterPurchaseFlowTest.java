package com.laundry.transaction.service;

import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.PaymentStatus;
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
 * Integration tests for Staff Counter purchase flow
 */
@SpringBootTest
@ActiveProfiles("test")
class StaffCounterPurchaseFlowTest {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private com.laundry.payment.service.PaymentService paymentService;

    @Test
    void testStaffCashPurchaseFlow() {
        UUID customerId = UUID.randomUUID();

        // Staff creates purchase
        CreatePurchaseRequest request = CreatePurchaseRequest.builder()
            .customerId(customerId)
            .serviceType(ServiceType.SELF_SERVICE)
            .machineId(UUID.randomUUID())
            .build();

        TransactionResponse transaction = purchaseService.createPurchase(request);
        assertEquals(TransactionStatus.PENDING_PAYMENT, transaction.getStatus());

        // Create CASH payment and confirm immediately
        CreatePaymentRequest paymentRequest = CreatePaymentRequest.builder()
            .transactionId(transaction.getTransactionId())
            .amount(transaction.getTotalAmount())
            .paymentMethod(com.laundry.payment.entity.PaymentMethod.CASH)
            .idempotencyKey("staff-cash-" + UUID.randomUUID())
            .build();

        PaymentResponse payment = paymentService.createPayment(paymentRequest);
        
        // Staff confirms cash payment
        PaymentResponse confirmedPayment = paymentService.confirmCashPayment(payment.getPaymentId());

        // Verify payment is PAID
        assertEquals(PaymentStatus.PAID, confirmedPayment.getStatus());
        assertNotNull(confirmedPayment.getPaidAt());

        // Verify transaction confirmed
        TransactionResponse confirmedTransaction = purchaseService.getTransaction(transaction.getTransactionId());
        assertEquals(TransactionStatus.PAID, confirmedTransaction.getStatus());
    }
}
