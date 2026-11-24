package com.laundry.payment;

import com.laundry.common.error.BusinessException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PaymentControllers {

    private PaymentControllers() {
    }

    /**
     * Webhook payOS. Luôn trả 200 cho payOS (kể cả khi bỏ qua), để payOS không gửi lại vô hạn;
     * chữ ký sai thì chỉ lưu lại, không ghi nhận tiền.
     */
    @RestController
    @RequiredArgsConstructor
    @Slf4j
    @Tag(name = "payOS webhook")
    public static class PayOsWebhookController {
        private final PaymentService payments;
        private final PayOsGateway gateway;
        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;

        @PostMapping("/api/payments/payos/webhook")
        public Map<String, Object> webhook(@RequestBody JsonNode body) {
            JsonNode data = body.path("data");
            String signature = body.path("signature").asString(null);
            boolean valid = gateway.verify(data, signature);
            long orderCode = data.path("orderCode").asLong(0);
            String reference = data.path("reference").asString("");
            String key = orderCode + ":" + reference;
            List<Long> ids = jdbc.queryForList("""
                    insert into payment_webhook_events (provider, event_key, signature_valid, payload)
                    values ('PAYOS', ?, ?, cast(? as jsonb)) on conflict (provider, event_key) do nothing returning id
                    """, Long.class, key, valid, mapper.writeValueAsString(body));
            if (ids.isEmpty()) return Map.of("success", true, "duplicate", true);
            long eventId = ids.get(0);
            if (!valid) {
                log.warn("payOS webhook with invalid signature for orderCode {}", orderCode);
                jdbc.update("update payment_webhook_events set error = 'invalid signature' where id = ?", eventId);
                return Map.of("success", true);
            }
            boolean paid = "00".equals(body.path("code").asString("")) && "00".equals(data.path("code").asString("00"));
            try {
                if (paid) {
                    payments.onGatewayPaid(orderCode, data.path("amount").asLong(0), reference,
                            data.path("counterAccountBankId").asString(null),
                            data.path("counterAccountNumber").asString(null),
                            data.path("counterAccountName").asString(null), eventId);
                }
                jdbc.update("update payment_webhook_events set processed_at = now() where id = ?", eventId);
            } catch (RuntimeException e) {
                log.error("payOS webhook {} failed", key, e);
                jdbc.update("update payment_webhook_events set error = ? where id = ?",
                        String.valueOf(e.getMessage()).substring(0, Math.min(500, String.valueOf(e.getMessage()).length())), eventId);
            }
            return Map.of("success", true);
        }
    }

    /**
     * Giả lập khách đã chuyển khoản đúng QR. Chỉ nhận khi cổng thanh toán đang giả lập
     * (PAYMENT_SIMULATION_ENABLED=true hoặc chưa cấu hình khóa payOS), khớp với lúc phiếu thu được tạo QR giả;
     * có khóa payOS thật thì luôn trả 404.
     */
    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Dev · Giả lập thanh toán")
    public static class SimulationController {
        private final PaymentService payments;
        private final PayOsGateway gateway;

        public record SimulateBody(Long amount) {
        }

        @PostMapping("/api/dev/payments/{intentId}/simulate")
        @Operation(summary = "Giả lập tiền về cho phiếu thu (mặc định đúng số tiền)")
        public Map<String, Object> simulate(@PathVariable UUID intentId, @RequestBody(required = false) SimulateBody body) {
            if (!gateway.simulated()) throw BusinessException.notFound("chức năng giả lập thanh toán");
            PaymentIntent i = payments.intent(intentId);
            if (i.getPayosOrderCode() == null) return Map.of("result", "NO_GATEWAY_PART");
            long amount = body != null && body.amount() != null ? body.amount() : i.getGatewayAmount();
            return payments.onGatewayPaid(i.getPayosOrderCode(), amount, "SIM" + UUID.randomUUID().toString()
                            .substring(0, 12), "970436", "0123456789", "NGUYEN VAN A", null)
                    .<Map<String, Object>>map(p -> Map.of("result", p.getStatus().name(), "receiptNo", p.getReceiptNo()))
                    .orElse(Map.of("result", "IGNORED"));
        }
    }
}
