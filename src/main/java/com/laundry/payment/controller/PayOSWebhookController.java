package com.laundry.payment.controller;

import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.gateway.payos.PayOSGateway;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.transaction.service.PurchaseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PayOS Webhook Controller
 * 
 * Handles PayOS payment callbacks and webhooks
 * Webhook URL: POST /api/payments/payos/webhook
 */
@RestController
@RequestMapping("/api/payments/payos")
@Slf4j
public class PayOSWebhookController {

    private final PaymentRepository paymentRepository;
    private final PurchaseService purchaseService;
    private final PayOSGateway payOSGateway;

    @Value("${app.payos.checksum-key:}")
    private String checksumKey;

    public PayOSWebhookController(PaymentRepository paymentRepository,
                                  PurchaseService purchaseService,
                                  @Autowired(required = false) PayOSGateway payOSGateway) {
        this.paymentRepository = paymentRepository;
        this.purchaseService = purchaseService;
        this.payOSGateway = payOSGateway;
    }

    /**
     * Handle PayOS webhook callback
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> handleWebhook(@RequestBody Map<String, Object> payload) {
        log.info("PayOS webhook received: {}", payload);

        try {
            // Extract data object
            Map<String, Object> data = payload;
            if (payload.get("data") instanceof Map<?, ?> dataMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) dataMap;
                data = casted;
            }

            // Verify webhook signature if available
            String signature = (String) payload.get("signature");
            if (payOSGateway != null && signature != null && !signature.isBlank()) {
                boolean valid = payOSGateway.verifyWebhookData(data, signature);
                if (!valid) {
                    log.warn("Invalid PayOS webhook signature: {}", signature);
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(Map.of("status", "error", "message", "Invalid signature"));
                }
            }

            String code = String.valueOf(payload.getOrDefault("code", data.getOrDefault("code", "00")));
            Object orderCodeObj = data.get("orderCode");
            if (orderCodeObj == null) {
                orderCodeObj = payload.get("orderCode");
            }
            if (orderCodeObj == null) {
                log.warn("Missing orderCode in PayOS webhook payload: {}", payload);
                return ResponseEntity.ok(Map.of("status", "error", "message", "Missing orderCode"));
            }

            String orderCodeStr = String.valueOf(orderCodeObj);
            String desc = String.valueOf(payload.getOrDefault("desc", data.getOrDefault("desc", "Success")));

            log.info("Processing PayOS webhook for orderCode: {}, code: {}", orderCodeStr, code);

            // Find payment by orderCode (stored in gatewayTransactionId)
            Optional<Payment> paymentOpt = paymentRepository.findByGatewayTransactionId(orderCodeStr);
            if (paymentOpt.isEmpty()) {
                // Fallback search
                paymentOpt = paymentRepository.findAll().stream()
                        .filter(p -> orderCodeStr.equals(p.getGatewayTransactionId()))
                        .findFirst();
            }

            if (paymentOpt.isEmpty()) {
                log.warn("Payment not found for orderCode: {}", orderCodeStr);
                return ResponseEntity.ok(Map.of("status", "error", "message", "Payment not found"));
            }

            Payment payment = paymentOpt.get();

            // Check if payment was successful
            if ("00".equals(code) || "0".equals(code)) {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.setStatus(PaymentStatus.PAID);
                    payment.setPaidAt(LocalDateTime.now());
                    payment.setGatewayResponse(desc);
                    paymentRepository.save(payment);

                    // Confirm transaction and activate resources
                    purchaseService.confirmPayment(payment.getTransactionId());

                    log.info("PayOS payment confirmed successfully: paymentId={}, orderCode={}",
                            payment.getId(), orderCodeStr);
                } else {
                    log.info("Payment already in status {}: paymentId={}", payment.getStatus(), payment.getId());
                }
            } else {
                // Payment cancelled or failed
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.setStatus(PaymentStatus.FAILED);
                    payment.setGatewayResponse(desc);
                    paymentRepository.save(payment);

                    // Cancel transaction and release held resources
                    purchaseService.cancelTransaction(payment.getTransactionId());

                    log.info("PayOS payment marked as failed: paymentId={}, orderCode={}, reason={}",
                            payment.getId(), orderCodeStr, desc);
                }
            }

            return ResponseEntity.ok(Map.of("status", "success"));

        } catch (Exception e) {
            log.error("Error processing PayOS webhook", e);
            return ResponseEntity.ok(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Get payment status from PayOS
     */
    @GetMapping("/status/{paymentId}")
    public ResponseEntity<?> getPaymentStatus(@PathVariable UUID paymentId) {
        log.info("Check PayOS status for paymentId: {}", paymentId);

        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "paymentId", payment.getId(),
                "status", payment.getStatus(),
                "gatewayTransactionId", payment.getGatewayTransactionId() != null ? payment.getGatewayTransactionId() : ""
        ));
    }
}
