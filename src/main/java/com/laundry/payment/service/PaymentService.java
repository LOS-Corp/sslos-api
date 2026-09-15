package com.laundry.payment.service;

import com.laundry.payment.dto.request.CreatePaymentRequest;
import com.laundry.payment.dto.request.PaymentCallbackRequest;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentMethod;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.exception.PaymentException;
import com.laundry.payment.gateway.PaymentGateway;
import com.laundry.payment.gateway.PaymentResult;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.transaction.entity.TransactionStatus;
import com.laundry.transaction.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Payment Service - handles all payment processing
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;
    private final PurchaseService purchaseService;

    @Value("${app.payment.callback-base-url:http://localhost:8080}")
    private String callbackBaseUrl;

    /**
     * Create a new payment
     */
    @Transactional
    public PaymentResponse createPayment(CreatePaymentRequest request) {
        log.info("Creating payment: transactionId={}, amount={}, method={}", 
                 request.getTransactionId(), request.getAmount(), request.getPaymentMethod());

        // Check idempotency - prevent duplicate payment
        if (request.getIdempotencyKey() != null) {
            var existing = paymentRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                log.info("Payment already exists with idempotency key: {}", request.getIdempotencyKey());
                return toResponse(existing.get());
            }
        }

        // Check if transaction is in PENDING_PAYMENT status
        var transaction = purchaseService.getTransaction(request.getTransactionId());
        if (transaction.getStatus() != TransactionStatus.PENDING_PAYMENT) {
            throw new PaymentException("Transaction is not in PENDING_PAYMENT status");
        }

        // Create payment record
        Payment payment = Payment.builder()
            .transactionId(request.getTransactionId())
            .amount(request.getAmount())
            .paymentMethod(request.getPaymentMethod())
            .status(PaymentStatus.PENDING)
            .idempotencyKey(request.getIdempotencyKey())
            .build();

        payment = paymentRepository.save(payment);

        // For ONLINE payment, create gateway request
        if (request.getPaymentMethod() == PaymentMethod.ONLINE) {
            String returnUrl = callbackBaseUrl + "/api/payments/callback/" + payment.getId();
            PaymentResult result = paymentGateway.createPayment(payment.getId(), request.getAmount(), returnUrl);
            
            if (!result.isSuccess()) {
                payment.markAsFailed(result.getErrorMessage());
                paymentRepository.save(payment);
                throw new PaymentException("Failed to create payment: " + result.getErrorMessage());
            }

            // Store gateway transaction ID
            payment.setGatewayTransactionId(result.getTransactionReference());
            payment = paymentRepository.save(payment);

            return toResponse(payment, result.getPaymentUrl(), result.getQrCode());
        }

        // For CASH payment, return without payment URL
        return toResponse(payment);
    }

    /**
     * Process payment callback from gateway
     */
    @Transactional
    public PaymentResponse processCallback(UUID paymentId, PaymentCallbackRequest request) {
        log.info("Processing payment callback: paymentId={}, status={}", paymentId, request.getStatus());

        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));

        // Idempotency check - if already paid, return existing
        if (payment.getStatus() == PaymentStatus.PAID) {
            log.info("Payment already processed: paymentId={}", paymentId);
            return toResponse(payment);
        }

        // Verify callback
        if (!paymentGateway.verifyCallback(request.getTransactionId(), request.getSignature())) {
            throw new PaymentException("Invalid callback signature");
        }

        // Update payment status
        if ("SUCCESS".equalsIgnoreCase(request.getStatus())) {
            payment.markAsPaid(request.getGatewayTransactionId(), request.getMessage());
            paymentRepository.save(payment);

            // Confirm transaction
            purchaseService.confirmPayment(payment.getTransactionId());

            log.info("Payment confirmed: paymentId={}, transactionId={}", paymentId, payment.getTransactionId());
        } else {
            payment.markAsFailed(request.getMessage());
            paymentRepository.save(payment);
            
            // Cancel transaction to release resources
            purchaseService.cancelTransaction(payment.getTransactionId());
            
            log.info("Payment failed: paymentId={}, reason={}", paymentId, request.getMessage());
        }

        return toResponse(payment);
    }

    /**
     * Confirm CASH payment (called by Staff)
     */
    @Transactional
    public PaymentResponse confirmCashPayment(UUID paymentId) {
        log.info("Confirming cash payment: paymentId={}", paymentId);

        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));

        if (payment.getPaymentMethod() != PaymentMethod.CASH) {
            throw new PaymentException("Payment is not a CASH payment");
        }

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new PaymentException("Payment is not in PENDING status");
        }

        // Update payment
        payment.markAsPaid(null, "Confirmed by staff");
        paymentRepository.save(payment);

        // Confirm transaction
        purchaseService.confirmPayment(payment.getTransactionId());

        log.info("Cash payment confirmed: paymentId={}, transactionId={}", paymentId, payment.getTransactionId());

        return toResponse(payment);
    }

    /**
     * Cancel a pending payment
     */
    @Transactional
    public void cancelPayment(UUID paymentId) {
        log.info("Cancelling payment: paymentId={}", paymentId);

        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new PaymentException("Only PENDING payments can be cancelled");
        }

        payment.setStatus(PaymentStatus.CANCELLED);
        paymentRepository.save(payment);

        // Cancel transaction
        purchaseService.cancelTransaction(payment.getTransactionId());

        log.info("Payment cancelled: paymentId={}", paymentId);
    }

    /**
     * Get payment by ID
     */
    public PaymentResponse getPayment(UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
        return toResponse(payment);
    }

    /**
     * Convert entity to response DTO
     */
    private PaymentResponse toResponse(Payment payment) {
        return toResponse(payment, null, null);
    }

    private PaymentResponse toResponse(Payment payment, String paymentUrl) {
        return toResponse(payment, paymentUrl, null);
    }

    private PaymentResponse toResponse(Payment payment, String paymentUrl, String qrCode) {
        return PaymentResponse.builder()
            .paymentId(payment.getId())
            .transactionId(payment.getTransactionId())
            .amount(payment.getAmount())
            .paymentMethod(payment.getPaymentMethod())
            .status(payment.getStatus())
            .gatewayTransactionId(payment.getGatewayTransactionId())
            .paymentUrl(paymentUrl)
            .qrCode(qrCode)
            .createdAt(payment.getCreatedAt())
            .paidAt(payment.getPaidAt())
            .build();
    }
}
