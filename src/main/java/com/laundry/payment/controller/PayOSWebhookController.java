package com.laundry.payment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.payment.dto.request.PaymentCallbackRequest;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.payment.service.PaymentService;
import com.laundry.transaction.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 */
@RestController
@RequestMapping("/api/payments/payos")
@RequiredArgsConstructor
@Slf4j
public class PayOSWebhookController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final PurchaseService purchaseService;
    private final ObjectMapper objectMapper;

    @Value("${app.payos.checksum-key:}")
    private String checksumKey;

    /**
     * Handle PayOS webhook callback
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> handleWebhook(@RequestBody Map<String, Object> payload) {
        log.info("PayOS webhook received: {}", payload);

        try {
            // Parse webhook data
            String code = String.valueOf(payload.get("code"));
            long orderCode = Long.parseLong(String.valueOf(payload.get("orderCode")));
            String desc = String.valueOf(payload.get("desc"));
            
            // Find payment by order code
            Optional<Payment> paymentOpt = paymentRepository.findAll().stream()
                .filter(p -> String.valueOf(generateOrderCode(p.getId())).equals(String.valueOf(orderCode)))
                .findFirst();

            if (paymentOpt.isEmpty()) {
                log.warn("Payment not found for orderCode: {}", orderCode);
                return ResponseEntity.ok(Map.of("status", "error", "message", "Payment not found"));
            }

            Payment payment = paymentOpt.get();

            // Process based on status
            if ("00".equals(code) || "07".equals(code)) {
                // Payment successful or awaiting confirmation
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.setStatus(PaymentStatus.PAID);
                    payment.setPaidAt(LocalDateTime.now());
                    payment.setGatewayResponse(desc);
                    paymentRepository.save(payment);

                    // Confirm transaction
                    purchaseService.confirmPayment(payment.getTransactionId());
                    
                    log.info("PayOS payment confirmed: paymentId={}, orderCode={}", payment.getId(), orderCode);
                }
            } else {
                // Payment failed or cancelled
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.setStatus(PaymentStatus.FAILED);
                    payment.setGatewayResponse(desc);
                    paymentRepository.save(payment);

                    // Cancel transaction to release resources
                    purchaseService.cancelTransaction(payment.getTransactionId());
                    
                    log.info("PayOS payment failed: paymentId={}, orderCode={}, reason={}", 
                             payment.getId(), orderCode, desc);
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
        
        Payment payment = paymentRepository.findById(paymentId)
            .orElse(null);
        
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(Map.of(
            "paymentId", payment.getId(),
            "status", payment.getStatus(),
            "gatewayTransactionId", payment.getGatewayTransactionId() != null ? payment.getGatewayTransactionId() : ""
        ));
    }

    /**
     * Generate order code from payment ID (same as PayOSGateway)
     */
    private long generateOrderCode(UUID paymentId) {
        String uuidDigits = paymentId.toString().replace("-", "");
        long orderCode = Math.abs(uuidDigits.hashCode() % 100000000000L);
        if (orderCode < 10000000000L) {
            orderCode += 10000000000L;
        }
        return orderCode;
    }
}
