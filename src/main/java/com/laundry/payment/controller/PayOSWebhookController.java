package com.laundry.payment.controller;

import com.laundry.payment.entity.Payment;
import com.laundry.payment.gateway.payos.PayOSGateway;
import com.laundry.payment.service.PaymentService;
import com.laundry.payment.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
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
public class PayOSWebhookController {

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final PayOSGateway payOSGateway;

    @Value("${app.payos.checksum-key:}")
    private String checksumKey;

    public PayOSWebhookController(PaymentRepository paymentRepository,
                                  PaymentService paymentService,
                                  @Autowired(required = false) PayOSGateway payOSGateway) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.payOSGateway = payOSGateway;
    }

    /**
     * Handle PayOS webhook callback
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> handleWebhook(@RequestBody Map<String, Object> payload) {
        try {
            if (payOSGateway == null || checksumKey == null || checksumKey.isBlank()) {
                log.error("PayOS webhook rejected because signature verification is not configured");
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "error", "message", "Webhook verification is unavailable"));
            }
            if (!(payload.get("data") instanceof Map<?, ?> rawData)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "Missing webhook data"));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) rawData;
            String signature = payload.get("signature") instanceof String value ? value : null;
            if (!payOSGateway.verifyWebhookData(data, signature)) {
                log.warn("Rejected PayOS webhook with invalid signature");
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "Invalid signature"));
            }

            Object orderCodeObj = data.get("orderCode");
            Object amountObj = data.get("amount");
            if (orderCodeObj == null || amountObj == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "Missing orderCode or amount"));
            }

            String orderCodeStr = String.valueOf(orderCodeObj);
            Payment payment = paymentRepository.findByGatewayTransactionId(orderCodeStr).orElse(null);
            if (payment == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("status", "error", "message", "Payment not found"));
            }
            BigDecimal receivedAmount;
            try {
                receivedAmount = new BigDecimal(String.valueOf(amountObj));
            } catch (NumberFormatException e) {
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "Invalid payment amount"));
            }
            if (receivedAmount.compareTo(payment.getAmount()) != 0) {
                log.error("Rejected PayOS webhook amount mismatch: paymentId={}", payment.getId());
                return ResponseEntity.badRequest()
                        .body(Map.of("status", "error", "message", "Payment amount mismatch"));
            }

            String code = String.valueOf(payload.getOrDefault("code", ""));
            String desc = String.valueOf(payload.getOrDefault("desc", ""));
            paymentService.processPayOSWebhook(payment.getId(),
                    "00".equals(code) || "0".equals(code),
                    orderCodeStr, desc);
            return ResponseEntity.ok(Map.of("status", "success"));

        } catch (Exception e) {
            log.error("Error processing PayOS webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", "Webhook processing failed"));
        }
    }

    /**
     * Get payment status from PayOS
     */
    @GetMapping("/status/{paymentId}")
    public ResponseEntity<?> getPaymentStatus(@PathVariable UUID paymentId, Authentication authentication) {
        log.info("Check PayOS status for paymentId: {}", paymentId);
        if (authentication == null || authentication.getName() == null
                || authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer account is required");
        }
        UUID customerId;
        try {
            customerId = UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid customer identity");
        }
        var payment = paymentService.refreshPayOSStatusForCustomer(paymentId, customerId);
        return ResponseEntity.ok(Map.of(
                "paymentId", payment.getPaymentId(),
                "status", payment.getStatus(),
                "gatewayTransactionId", payment.getGatewayTransactionId() != null ? payment.getGatewayTransactionId() : ""
        ));
    }
}
