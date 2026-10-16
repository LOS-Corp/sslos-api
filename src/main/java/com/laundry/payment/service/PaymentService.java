package com.laundry.payment.service;

import com.laundry.payment.dto.request.CreatePaymentRequest;
import com.laundry.payment.dto.request.PaymentCallbackRequest;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentMethod;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.exception.PaymentException;
import com.laundry.payment.gateway.GatewayPaymentStatus;
import com.laundry.payment.gateway.PaymentGateway;
import com.laundry.payment.gateway.PaymentResult;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.transaction.dto.response.TransactionResponse;
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
    private final ScheduledBookingService scheduledBookingService;

    @Value("${app.payment.callback-base-url:http://localhost:8080}")
    private String callbackBaseUrl;

    @Value("${app.payos.return-url:}")
    private String payosReturnUrl;

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
                Payment payment = existing.get();
                if (!payment.getTransactionId().equals(request.getTransactionId())
                        || payment.getAmount().compareTo(request.getAmount()) != 0
                        || payment.getPaymentMethod() != request.getPaymentMethod()) {
                    throw new PaymentException("Idempotency key was already used for a different payment");
                }

                log.info("Payment already exists with idempotency key: {}", request.getIdempotencyKey());
                return toResponse(existing.get());
            }
        }

        // Check if transaction is in PENDING_PAYMENT status
        var transaction = purchaseService.getTransaction(request.getTransactionId());
        if (transaction.getStatus() != TransactionStatus.PENDING_PAYMENT) {
            throw new PaymentException("Transaction is not in PENDING_PAYMENT status");
        }
        if (request.getAmount().compareTo(transaction.getTotalAmount()) != 0) {
            throw new PaymentException("Payment amount does not match transaction total");
        }
        try {
            request.getAmount().longValueExact();
        } catch (ArithmeticException e) {
            throw new PaymentException("PayOS payment amount must be a whole VND amount");
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

        // Payments are online-only: always create the gateway request
        String returnUrl = payosReturnUrl == null || payosReturnUrl.isBlank()
                ? callbackBaseUrl + "/api/payments/callback/" + payment.getId()
                : payosReturnUrl + (payosReturnUrl.contains("?") ? "&" : "?")
                + "paymentId=" + payment.getId();
        PaymentResult result = paymentGateway.createPayment(payment.getId(), request.getAmount(), returnUrl);

        if (!result.isSuccess()) {
            payment.markAsFailed(result.getErrorMessage());
            paymentRepository.save(payment);
            throw new PaymentException("Failed to create payment: " + result.getErrorMessage());
        }

        // Store gateway transaction ID
        payment.setGatewayTransactionId(result.getTransactionReference());
        payment.setCheckoutUrl(result.getPaymentUrl());
        payment.setQrCode(result.getQrCode());
        payment = paymentRepository.save(payment);

        return toResponse(payment);
    }

    @Transactional
    public PaymentResponse createPaymentForCustomer(CreatePaymentRequest request, UUID customerId) {
        TransactionResponse transaction = purchaseService.getTransaction(request.getTransactionId());
        if (!customerId.equals(transaction.getCustomerId())) {
            throw new PaymentException("Transaction does not belong to the authenticated customer");
        }
        return createPayment(request);
    }

    @Transactional
    public PaymentResponse createBookingPayment(UUID bookingId,
                                                UUID expectedTransactionId,
                                                String idempotencyKey,
                                                UUID customerId) {
        UUID transactionId = scheduledBookingService.prepareCheckout(customerId, bookingId);
        if (!transactionId.equals(expectedTransactionId)) {
            throw new PaymentException("Transaction does not match booking");
        }
        TransactionResponse transaction = purchaseService.getTransaction(transactionId);
        return createPaymentForCustomer(CreatePaymentRequest.builder()
                .transactionId(transactionId)
                .amount(transaction.getTotalAmount())
                .paymentMethod(PaymentMethod.ONLINE)
                .idempotencyKey(idempotencyKey)
                .build(), customerId);
    }

    /**
     * Process payment callback from gateway
     */
    @Transactional
    public PaymentResponse processCallback(UUID paymentId, PaymentCallbackRequest request) {
        log.info("Processing payment callback: paymentId={}, status={}", paymentId, request.getStatus());

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
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
            confirmTransactionPayment(payment.getTransactionId());

            log.info("Payment confirmed: paymentId={}, transactionId={}", paymentId, payment.getTransactionId());
        } else {
            payment.markAsFailed(request.getMessage());
            paymentRepository.save(payment);
            
            // Cancel transaction to release resources
            cancelAssociatedTransaction(payment.getTransactionId());
            
            log.info("Payment failed: paymentId={}, reason={}", paymentId, request.getMessage());
        }

        return toResponse(payment);
    }

    /**
     * Cancel a pending payment
     */
    @Transactional
    public void cancelPayment(UUID paymentId) {
        log.info("Cancelling payment: paymentId={}", paymentId);

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new PaymentException("Only PENDING payments can be cancelled");
        }

        payment.setStatus(PaymentStatus.CANCELLED);
        paymentRepository.save(payment);

        // Cancel transaction
        cancelAssociatedTransaction(payment.getTransactionId());

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

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentForCustomer(UUID paymentId, UUID customerId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
        ensureCustomerOwnsTransaction(payment.getTransactionId(), customerId);
        return toResponse(payment);
    }

    @Transactional
    public PaymentResponse refreshPayOSStatusForCustomer(UUID paymentId, UUID customerId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
        ensureCustomerOwnsTransaction(payment.getTransactionId(), customerId);
        if (payment.getStatus() != PaymentStatus.PENDING || payment.getGatewayTransactionId() == null) {
            return toResponse(payment);
        }

        var gatewayStatus = paymentGateway.getPaymentStatus(payment.getGatewayTransactionId());
        if (gatewayStatus.isEmpty() || !"PAID".equalsIgnoreCase(gatewayStatus.get().status())) {
            return toResponse(payment);
        }

        GatewayPaymentStatus confirmed = gatewayStatus.get();
        if (!payment.getGatewayTransactionId().equals(confirmed.transactionReference())
                || confirmed.amount() == null
                || confirmed.amount().compareTo(payment.getAmount()) != 0) {
            throw new PaymentException("PayOS payment status does not match the pending payment");
        }

        processPayOSWebhook(paymentId, true, confirmed.transactionReference(),
                "Payment confirmed by PayOS status lookup");
        return getPaymentForCustomer(paymentId, customerId);
    }

    @Transactional
    public PaymentResponse simulatePaymentSuccessForCustomer(UUID paymentId, UUID customerId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
        ensureCustomerOwnsTransaction(payment.getTransactionId(), customerId);
        if (payment.getPaymentMethod() != PaymentMethod.ONLINE) {
            throw new PaymentException("Only online payments can be simulated");
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new PaymentException("Only PENDING payments can be simulated");
        }

        String gatewayReference = "LOCAL_SIM_" + UUID.randomUUID();
        payment.markAsPaid(gatewayReference, "Local development payment simulation");
        paymentRepository.save(payment);
        confirmTransactionPayment(payment.getTransactionId());
        log.info("Local payment simulation completed: paymentId={}", paymentId);
        return toResponse(payment);
    }

    @Transactional
    public void cancelPaymentForCustomer(UUID paymentId, UUID customerId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
        ensureCustomerOwnsTransaction(payment.getTransactionId(), customerId);
        cancelPayment(paymentId);
    }

    private void ensureCustomerOwnsTransaction(UUID transactionId, UUID customerId) {
        if (!customerId.equals(purchaseService.getTransaction(transactionId).getCustomerId())) {
            throw new PaymentException("Payment does not belong to the authenticated customer");
        }
    }

    /**
     * Convert entity to response DTO
     */
    private PaymentResponse toResponse(Payment payment) {
        return toResponse(payment, payment.getCheckoutUrl(), payment.getQrCode());
    }

    @Transactional
    public void processPayOSWebhook(UUID paymentId, boolean successful, String gatewayReference, String message) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new PaymentException("Payment not found: " + paymentId));
        if (payment.getStatus() == PaymentStatus.PAID) {
            return;
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return;
        }
        if (successful) {
            payment.markAsPaid(gatewayReference, message);
            paymentRepository.save(payment);
            confirmTransactionPayment(payment.getTransactionId());
        } else {
            payment.markAsFailed(message);
            paymentRepository.save(payment);
            cancelAssociatedTransaction(payment.getTransactionId());
        }
    }

    private void cancelAssociatedTransaction(UUID transactionId) {
        if (scheduledBookingService.hasScheduledBooking(transactionId)) {
            scheduledBookingService.cancelForPaymentFailure(transactionId);
        } else {
            purchaseService.cancelTransaction(transactionId);
        }
    }

    @Transactional
    public void confirmTransactionPayment(UUID transactionId) {
        if (scheduledBookingService.hasScheduledBooking(transactionId)) {
            scheduledBookingService.confirmPayment(transactionId);
        } else {
            purchaseService.confirmPayment(transactionId);
        }
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
            .paymentUrl(paymentUrl != null ? paymentUrl : payment.getCheckoutUrl())
            .qrCode(qrCode != null ? qrCode : payment.getQrCode())
            .createdAt(payment.getCreatedAt())
            .paidAt(payment.getPaidAt())
            .build();
    }
}
