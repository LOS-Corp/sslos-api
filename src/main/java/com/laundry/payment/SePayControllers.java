package com.laundry.payment;

import com.laundry.common.Tokens;
import com.laundry.order.OrderRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;

/** Cổng thanh toán SePay: trang chuyển sang SePay, IPN, và job hỏi trạng thái đơn (chạy được trên localhost). */
public final class SePayControllers {

    private SePayControllers() {
    }

    @RestController
    @RequiredArgsConstructor
    @Slf4j
    @Tag(name = "SePay")
    public static class SePayController {
        private final PaymentService payments;
        private final PaymentIntentRepository intents;
        private final OrderRepository orders;
        private final SePayGateway sepay;
        private final SePayProperties props;
        private final JdbcTemplate jdbc;
        private final ObjectMapper mapper;
        private final Clock clock;

        /**
         * Trang khách mở (nút "Mở trang thanh toán" / quét QR ở kiosk): tự gửi form có chữ ký sang SePay. Phiếu đã trả,
         * hết hạn hoặc bị hủy thì báo lại thay vì chuyển đi.
         */
        @GetMapping(value = "/api/payments/sepay/checkout/{code}", produces = MediaType.TEXT_HTML_VALUE)
        @Operation(summary = "Chuyển khách sang trang thanh toán SePay (form POST có chữ ký)")
        public ResponseEntity<String> checkout(@PathVariable long code) {
            PaymentIntent intent = intents.findByPayosOrderCode(code)
                    .filter(i -> i.getGateway() == PaymentIntent.Gateway.SEPAY).orElse(null);
            if (intent == null) return page(HttpStatus.NOT_FOUND, "Không tìm thấy phiếu thanh toán.");
            if (intent.getStatus() == PaymentIntent.Status.PAID) return page(HttpStatus.OK, "Phiếu này đã được thanh toán.");
            if (intent.getStatus() != PaymentIntent.Status.PENDING || clock.instant().isAfter(intent.getExpiresAt())) {
                return page(HttpStatus.GONE, "Phiếu thanh toán đã hết hạn. Quay lại ứng dụng / kiosk để tạo phiếu mới.");
            }
            if (!sepay.configured()) return page(HttpStatus.SERVICE_UNAVAILABLE, "Cửa hàng chưa cấu hình SePay.");
            String orderCode = orders.findById(intent.getOrderId()).map(o -> o.getCode()).orElse("");
            Map<String, String> form = sepay.checkoutForm(code, intent.getGatewayAmount(), "Thanh toan don " + orderCode);
            StringBuilder inputs = new StringBuilder();
            form.forEach((k, v) -> inputs.append("<input type=\"hidden\" name=\"").append(HtmlUtils.htmlEscape(k))
                    .append("\" value=\"").append(HtmlUtils.htmlEscape(v)).append("\">"));
            String html = """
                    <!doctype html><html lang="vi"><head><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1"><title>Đang chuyển sang SePay…</title></head>
                    <body style="font-family:system-ui,sans-serif;display:grid;place-items:center;min-height:90vh;color:#334155">
                    <form id="f" method="POST" action="%s">%s
                    <p>Đang chuyển sang trang thanh toán SePay…</p><button type="submit">Tiếp tục</button></form>
                    <script>document.getElementById('f').submit()</script></body></html>
                    """.formatted(HtmlUtils.htmlEscape(sepay.checkoutAction()), inputs);
            return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                    .header("Cache-Control", "no-store").body(html);
        }

        /**
         * IPN của SePay (Cổng thanh toán → Cấu hình → IPN, xác thực SECRET_KEY): header X-Secret-Key phải khớp
         * SEPAY_IPN_SECRET_KEY. ORDER_PAID (order_status CAPTURED) thì ghi tiền cho phiếu SSLOS&lt;mã&gt;.
         */
        @PostMapping("/api/payments/sepay/ipn")
        @Operation(summary = "SePay báo đơn đã thanh toán (IPN)")
        public ResponseEntity<Map<String, Object>> ipn(@RequestHeader(value = "X-Secret-Key", required = false) String key,
                                                       @RequestBody JsonNode body) {
            boolean valid = SePayProperties.notBlank(props.ipnSecretKey()) && key != null
                    && Tokens.constantTimeEquals(props.ipnSecretKey().trim(), key.trim());
            JsonNode order = body.path("order");
            String invoice = order.path("order_invoice_number").asString("");
            String type = body.path("notification_type").asString("");
            String eventKey = "sepay:" + type + ":" + invoice + ":" + body.path("transaction").path("transaction_id").asString("");
            List<Long> ids = jdbc.queryForList("""
                    insert into payment_webhook_events (provider, event_key, signature_valid, payload)
                    values ('SEPAY', ?, ?, cast(? as jsonb)) on conflict (provider, event_key) do nothing returning id
                    """, Long.class, eventKey, valid, mapper.writeValueAsString(body));
            if (ids.isEmpty()) return ResponseEntity.ok(Map.of("success", true, "duplicate", true));
            long eventId = ids.get(0);
            if (!valid) {
                log.warn("SePay IPN for {} rejected: wrong or missing X-Secret-Key", invoice);
                jdbc.update("update payment_webhook_events set error = 'invalid secret key' where id = ?", eventId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("success", false));
            }
            try {
                Long code = SePayGateway.codeOf(invoice);
                if ("ORDER_PAID".equals(type) && "CAPTURED".equalsIgnoreCase(order.path("order_status").asString(""))
                        && code != null) {
                    long amount = (long) Double.parseDouble(order.path("order_amount").asString("0"));
                    payments.onGatewayPaid(Payment.Method.SEPAY, code, amount, invoice, null, null, null, eventId);
                }
                jdbc.update("update payment_webhook_events set processed_at = now() where id = ?", eventId);
            } catch (RuntimeException e) {
                log.error("SePay IPN for {} failed", invoice, e);
                String message = String.valueOf(e.getMessage());
                jdbc.update("update payment_webhook_events set error = ? where id = ?",
                        message.substring(0, Math.min(500, message.length())), eventId);
            }
            return ResponseEntity.ok(Map.of("success", true));
        }

        private static ResponseEntity<String> page(HttpStatus status, String message) {
            String html = "<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Thanh toán</title></head>"
                    + "<body style=\"font-family:system-ui,sans-serif;display:grid;place-items:center;min-height:90vh\">"
                    + "<p>" + HtmlUtils.htmlEscape(message) + "</p></body></html>";
            return ResponseEntity.status(status).contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                    .body(html);
        }
    }

    /**
     * Hỏi SePay trạng thái các phiếu SePay đang chờ (mỗi 15 giây): bù IPN bị lỡ và cho chạy thử trên localhost, nơi SePay
     * không gọi IPN tới được. Cùng mã đơn chỉ ghi một lần (provider_reference = order_invoice_number).
     */
    @Component
    @ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    @RequiredArgsConstructor
    @Slf4j
    public static class SePaySync {
        private final PaymentService payments;
        private final PaymentGateways gateways;
        private final SePayGateway sepay;
        private final JdbcTemplate jdbc;

        @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
        public void sync() {
            if (gateways.simulated() || !sepay.configured()) return;
            List<Map<String, Object>> pending = jdbc.queryForList("""
                    select payos_order_code, gateway_amount from payment_intents
                    where status = 'PENDING' and gateway = 'SEPAY' and payos_order_code is not null
                      and expires_at > now() - interval '2 minutes'
                    """);
            for (Map<String, Object> row : pending) {
                long code = ((Number) row.get("payos_order_code")).longValue();
                long expected = ((Number) row.get("gateway_amount")).longValue();
                sepay.status(code).filter(SePayGateway.OrderStatus::paid).ifPresent(s -> {
                    try {
                        payments.onGatewayPaid(Payment.Method.SEPAY, code, s.amount() > 0 ? s.amount() : expected,
                                SePayGateway.invoice(code), null, null, null, null);
                        log.info("SePay sync: {} paid", SePayGateway.invoice(code));
                    } catch (RuntimeException e) {
                        log.warn("SePay sync {} failed: {}", SePayGateway.invoice(code), e.getMessage());
                    }
                });
            }
        }
    }
}
