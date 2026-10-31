package com.laundry.payment.controller;

import com.laundry.shared.security.CurrentUser;
import com.laundry.payment.service.PaymentService;
import com.laundry.payment.service.PaymentWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
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
@RequiredArgsConstructor
public class PayOSWebhookController {

    private final PaymentWebhookService webhookService;
    private final PaymentService paymentService;

    /** Signed by payOS (HMAC), not by JWT; the outcome keeps the response format payOS expects. */
    @PostMapping("/webhook")
    public ResponseEntity<Map<String, String>> handleWebhook(@RequestBody Map<String, Object> payload) {
        try {
            PaymentWebhookService.Outcome outcome = webhookService.handle(payload);
            return ResponseEntity.status(outcome.httpStatus()).body(outcome.body());
        } catch (RuntimeException e) {
            log.error("Error processing PayOS webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Webhook processing failed"));
        }
    }

    /**
     * Get payment status from PayOS
     */
    @GetMapping("/status/{paymentId}")
    public ResponseEntity<?> getPaymentStatus(@PathVariable UUID paymentId, CurrentUser customer) {
        log.info("Check PayOS status for paymentId: {}", paymentId);
        var payment = paymentService.refreshPayOSStatusForCustomer(paymentId, customer.id());
        return ResponseEntity.ok(Map.of(
                "paymentId", payment.getPaymentId(),
                "status", payment.getStatus(),
                "gatewayTransactionId", payment.getGatewayTransactionId() != null ? payment.getGatewayTransactionId() : ""
        ));
    }
}
