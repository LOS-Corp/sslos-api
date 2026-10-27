package com.laundry.payment.service;

import com.laundry.payment.entity.Payment;
import com.laundry.payment.gateway.payos.PayOSGateway;
import com.laundry.payment.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * payOS webhook (FR-PAY-01): fail-closed without a checksum key, verify the HMAC signature, find the
 * payment by order code, compare the amount, then hand the outcome to {@link PaymentService}, which is
 * idempotent for repeated deliveries (SC-03, SC-26).
 */
@Slf4j
@Service
public class PaymentWebhookService {

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final PayOSGateway payOSGateway;
    private final String checksumKey;

    public PaymentWebhookService(PaymentRepository paymentRepository,
                                 PaymentService paymentService,
                                 @Autowired(required = false) PayOSGateway payOSGateway,
                                 @Value("${app.payos.checksum-key:}") String checksumKey) {
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.payOSGateway = payOSGateway;
        this.checksumKey = checksumKey;
    }

    public Outcome handle(Map<String, Object> payload) {
        if (payOSGateway == null || checksumKey == null || checksumKey.isBlank()) {
            log.error("PayOS webhook rejected because signature verification is not configured");
            return Outcome.UNAVAILABLE;
        }
        if (!(payload.get("data") instanceof Map<?, ?> rawData)) {
            return Outcome.MISSING_DATA;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) rawData;
        String signature = payload.get("signature") instanceof String value ? value : null;
        if (!payOSGateway.verifyWebhookData(data, signature)) {
            log.warn("Rejected PayOS webhook with invalid signature");
            return Outcome.INVALID_SIGNATURE;
        }
        Object orderCode = data.get("orderCode");
        Object amount = data.get("amount");
        if (orderCode == null || amount == null) {
            return Outcome.MISSING_ORDER_OR_AMOUNT;
        }
        String orderCodeText = String.valueOf(orderCode);
        Payment payment = paymentRepository.findByGatewayTransactionId(orderCodeText).orElse(null);
        if (payment == null) {
            return Outcome.PAYMENT_NOT_FOUND;
        }
        BigDecimal received;
        try {
            received = new BigDecimal(String.valueOf(amount));
        } catch (NumberFormatException e) {
            return Outcome.INVALID_AMOUNT;
        }
        if (received.compareTo(payment.getAmount()) != 0) {
            log.error("Rejected PayOS webhook amount mismatch: paymentId={}", payment.getId());
            return Outcome.AMOUNT_MISMATCH;
        }
        String code = String.valueOf(payload.getOrDefault("code", ""));
        String desc = String.valueOf(payload.getOrDefault("desc", ""));
        paymentService.processPayOSWebhook(payment.getId(), "00".equals(code) || "0".equals(code), orderCodeText, desc);
        return Outcome.PROCESSED;
    }

    /** Result of one delivery; the controller turns it into the HTTP answer payOS expects. */
    public enum Outcome {
        PROCESSED(200, "success", null),
        UNAVAILABLE(503, "error", "Webhook verification is unavailable"),
        MISSING_DATA(400, "error", "Missing webhook data"),
        INVALID_SIGNATURE(400, "error", "Invalid signature"),
        MISSING_ORDER_OR_AMOUNT(400, "error", "Missing orderCode or amount"),
        PAYMENT_NOT_FOUND(404, "error", "Payment not found"),
        INVALID_AMOUNT(400, "error", "Invalid payment amount"),
        AMOUNT_MISMATCH(400, "error", "Payment amount mismatch");

        private final int httpStatus;
        private final String status;
        private final String message;

        Outcome(int httpStatus, String status, String message) {
            this.httpStatus = httpStatus;
            this.status = status;
            this.message = message;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public Map<String, String> body() {
            return message == null ? Map.of("status", status) : Map.of("status", status, "message", message);
        }
    }
}
