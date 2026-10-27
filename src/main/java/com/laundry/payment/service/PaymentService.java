package com.laundry.payment.service;

import com.laundry.payment.dto.request.CreatePaymentRequest;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.Payment;
import com.laundry.shared.money.Money;
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

import java.math.BigDecimal;
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
    private final WalletPaymentService walletPayments;
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
        // FR-PAY-04: the wallet part (if any) is already paid; payOS collects only the remainder.
        BigDecimal remainder = transaction.getTotalAmount()
                .subtract(BigDecimal.valueOf(walletPayments.activeWalletPart(request.getTransactionId()).vnd()));
        if (request.getAmount().compareTo(remainder) != 0) {
            throw new PaymentException("Payment amount does not match the amount still due");
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
                ? callbackBaseUrl + "/?paymentId=" + payment.getId()
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
                                                UUID customerId,
                                                boolean useWallet) {
        // A repeated checkout returns the payment it already created, even once the booking is confirmed.
        var previous = paymentRepository.findByIdempotencyKey(idempotencyKey)
                .or(() -> paymentRepository.findByIdempotencyKey(idempotencyKey + ":wallet"));
        if (previous.isPresent() && previous.get().getTransactionId().equals(expectedTransactionId)) {
            ensureCustomerOwnsTransaction(expectedTransactionId, customerId);
            return toResponse(previous.get());
        }
        UUID transactionId = scheduledBookingService.prepareCheckout(customerId, bookingId);
        if (!transactionId.equals(expectedTransactionId)) {
            throw new PaymentException("Transaction does not match booking");
        }
        TransactionResponse transaction = purchaseService.getTransaction(transactionId);
        Money total = Money.of(transaction.getTotalAmount());
        // FR-PAY-04: wallet credit first; UC-02 5b: a wallet that covers everything confirms without payOS.
        Money walletPart = useWallet
                ? walletPayments.payFromWallet(customerId, transactionId, total, idempotencyKey)
                : walletPayments.activeWalletPart(transactionId);
        if (walletPart.compareTo(total) >= 0) {
            confirmTransactionPayment(transactionId);
            return paymentRepository.findByIdempotencyKey(idempotencyKey + ":wallet")
                    .map(this::toResponse)
                    .orElseThrow(() -> new PaymentException("Wallet payment was not recorded"));
        }
        return createPaymentForCustomer(CreatePaymentRequest.builder()
                .transactionId(transactionId)
                .amount(BigDecimal.valueOf(total.minus(walletPart).vnd()))
                .paymentMethod(PaymentMethod.ONLINE)
                .idempotencyKey(idempotencyKey)
                .build(), customerId);
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
        // SC-31: the rest of the payment failed, so the wallet part goes back to the customer.
        walletPayments.reverse(java.util.List.of(transactionId), "Hoàn phần ví do thanh toán payOS không thành công");
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
