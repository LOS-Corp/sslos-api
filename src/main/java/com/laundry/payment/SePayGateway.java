package com.laundry.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cổng thanh toán SePay. Không có API trả link: trình duyệt phải POST một form có chữ ký tới /v1/checkout/init, SePay
 * chuyển tiếp sang trang thanh toán. Backend dựng form đó ({@link #checkoutForm}) và phục vụ ở
 * GET /api/payments/sepay/checkout/{mã}; link này là checkoutUrl của phiếu thu.
 */
@Component
@Slf4j
public class SePayGateway {

    /** Các trường được ký, đúng thứ tự trong tài liệu SePay (trang "Form thanh toán"). */
    static final List<String> SIGNED_FIELDS = List.of("order_amount", "merchant", "currency", "operation",
            "order_description", "order_invoice_number", "customer_id", "payment_method", "success_url", "error_url",
            "cancel_url");

    /** Kết quả tra cứu một đơn ở SePay. {@code paid}: SePay đã ghi nhận thanh toán (CAPTURED). */
    public record OrderStatus(boolean paid, long amount, String transactionId) {
    }

    private final SePayProperties props;
    private final ObjectMapper mapper;
    private final RestClient http;

    public SePayGateway(SePayProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(props.apiBaseUrl()).requestFactory(factory).build();
    }

    public boolean configured() {
        return props.configured();
    }

    /** Mã đơn bên SePay (order_invoice_number) của phiếu có mã {@code code}: duy nhất, khớp IPN với phiếu. */
    public static String invoice(long code) {
        return "SSLOS" + code;
    }

    /** Mã phiếu từ order_invoice_number, hoặc null nếu không phải đơn của hệ thống. */
    public static Long codeOf(String invoice) {
        if (invoice == null || !invoice.startsWith("SSLOS")) return null;
        try {
            return Long.parseLong(invoice.substring(5));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Link backend mà khách mở để sang trang thanh toán SePay. */
    public String checkoutLink(long code) {
        return props.publicBase() + "/api/payments/sepay/checkout/" + code;
    }

    /** Nơi form được gửi tới. */
    public String checkoutAction() {
        return props.checkoutUrl();
    }

    /** Các trường của form thanh toán (đúng thứ tự tài liệu), kèm chữ ký. */
    public Map<String, String> checkoutForm(long code, long amount, String description) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("order_amount", Long.toString(amount));
        f.put("merchant", props.merchantId().trim());
        f.put("currency", "VND");
        f.put("operation", "PURCHASE");
        f.put("order_description", description);
        f.put("order_invoice_number", invoice(code));
        if (SePayProperties.notBlank(props.successUrl())) f.put("success_url", props.successUrl().trim());
        if (SePayProperties.notBlank(props.errorUrl())) f.put("error_url", props.errorUrl().trim());
        if (SePayProperties.notBlank(props.cancelUrl())) f.put("cancel_url", props.cancelUrl().trim());
        f.put("signature", sign(f, props.secretKey().trim()));
        return f;
    }

    /**
     * Chữ ký form: lấy các trường trong {@link #SIGNED_FIELDS} theo đúng thứ tự, bỏ trường rỗng hoặc bằng 0, nối "k=v"
     * bằng dấu phẩy rồi base64(HMAC-SHA256(chuỗi, secretKey)).
     */
    static String sign(Map<String, String> fields, String secretKey) {
        StringBuilder sb = new StringBuilder();
        for (String name : SIGNED_FIELDS) {
            String v = fields.get(name);
            if (v == null || v.isEmpty() || "0".equals(v)) continue;
            if (!sb.isEmpty()) sb.append(',');
            sb.append(name).append('=').append(v);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Không ký được form SePay", e);
        }
    }

    /**
     * Trạng thái đơn ở SePay (GET /v1/order/detail/{invoice}, Basic merchantId:secretKey). Tài liệu chưa công bố đủ
     * cấu trúc trả về, nên đọc mềm: tìm order_status / order_amount / transaction_id ở bất kỳ đâu trong JSON.
     * Chưa có đơn (khách chưa mở trang SePay) hoặc lỗi: empty.
     */
    public Optional<OrderStatus> status(long code) {
        if (!props.configured()) return Optional.empty();
        try {
            String body = http.get().uri("/v1/order/detail/{invoice}", invoice(code))
                    .header("Authorization", basicAuth()).accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(String.class);
            JsonNode json = mapper.readTree(body);
            String status = text(json.findValue("order_status"));
            String amount = text(json.findValue("order_amount"));
            return Optional.of(new OrderStatus("CAPTURED".equalsIgnoreCase(status),
                    amount == null ? 0 : (long) Double.parseDouble(amount), text(json.findValue("transaction_id"))));
        } catch (RuntimeException e) {
            log.debug("SePay order {} not available: {}", invoice(code), e.getMessage());
            return Optional.empty();
        }
    }

    /** Hủy đơn ở SePay khi phiếu hết hạn / bị thay (POST /v1/order/cancel); lỗi chỉ ghi log. */
    public void cancel(long code) {
        if (!props.configured()) return;
        try {
            http.post().uri("/v1/order/cancel").header("Authorization", basicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of("order_invoice_number", invoice(code))))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.debug("SePay cancel {} skipped: {}", invoice(code), e.getMessage());
        }
    }

    private String basicAuth() {
        String raw = props.merchantId().trim() + ":" + props.secretKey().trim();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }
}
