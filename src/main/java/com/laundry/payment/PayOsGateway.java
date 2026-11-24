package com.laundry.payment;

import com.laundry.common.Tokens;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * payOS (VietQR). Tài liệu: https://payos.vn/docs. Chưa cấu hình khóa, hoặc bật PAYMENT_SIMULATION_ENABLED,
 * thì chạy chế độ giả lập: QR giả, xác nhận bằng POST /api/dev/payments/{intentId}/simulate.
 */
@Component
@Slf4j
public class PayOsGateway {

    public record Link(String checkoutUrl, String qrCode, String paymentLinkId) {
    }

    private final PayOsProperties props;
    private final ObjectMapper mapper;
    private final boolean simulationEnabled;
    private final RestClient http;

    public PayOsGateway(PayOsProperties props, ObjectMapper mapper,
                        @Value("${app.payment.simulation-enabled:false}") boolean simulationEnabled) {
        this.props = props;
        this.mapper = mapper;
        this.simulationEnabled = simulationEnabled;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(props.baseUrl() == null ? "https://api-merchant.payos.vn"
                : props.baseUrl().replaceAll("/+$", "")).requestFactory(factory).build();
    }

    public boolean simulated() {
        return simulationEnabled || !props.configured();
    }

    /** Đủ 3 khóa payOS. */
    public boolean configured() {
        return props.configured();
    }

    /** Tạo link thanh toán có số tiền cố định và hạn {@code expiresAt}. */
    public Link create(long orderCode, long amount, String description, Instant expiresAt) {
        if (simulated()) {
            return new Link("sim://payos/" + orderCode, "SIMULATED|" + orderCode + "|" + amount, "sim-" + orderCode);
        }
        String desc = description.length() > 25 ? description.substring(0, 25) : description;
        String data = "amount=" + amount + "&cancelUrl=" + props.cancelUrl() + "&description=" + desc
                + "&orderCode=" + orderCode + "&returnUrl=" + props.returnUrl();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderCode", orderCode);
        body.put("amount", amount);
        body.put("description", desc);
        body.put("cancelUrl", props.cancelUrl());
        body.put("returnUrl", props.returnUrl());
        body.put("expiredAt", expiresAt.getEpochSecond());
        body.put("signature", Tokens.hmacSha256Hex(props.checksumKey(), data));
        try {
            String response = http.post().uri("/v2/payment-requests")
                    .header("x-client-id", props.clientId()).header("x-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsString(body))
                    .retrieve().body(String.class);
            JsonNode json = mapper.readTree(response);
            if (!"00".equals(json.path("code").asString(""))) {
                log.error("payOS refused orderCode {}: {} {}", orderCode, json.path("code").asString(""),
                        json.path("desc").asString(""));
                throw new BusinessException(ErrorCode.PAYMENT_GATEWAY);
            }
            JsonNode d = json.path("data");
            return new Link(d.path("checkoutUrl").asString(""), d.path("qrCode").asString(""),
                    d.path("paymentLinkId").asString(null));
        } catch (RestClientException e) {
            log.error("payOS create failed for {}: {}", orderCode, e.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY);
        }
    }

    /** Hủy link (phiếu hết hạn / đơn bị hủy); lỗi chỉ ghi log. */
    public void cancel(long orderCode, String reason) {
        if (simulated()) return;
        try {
            http.post().uri("/v2/payment-requests/{code}/cancel", orderCode)
                    .header("x-client-id", props.clientId()).header("x-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of("cancellationReason", reason)))
                    .retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("payOS cancel {} failed: {}", orderCode, e.getMessage());
        }
    }

    /**
     * Chữ ký webhook: HMAC-SHA256(checksumKey, các trường của {@code data} sắp theo tên, "k=v" nối bằng "&",
     * null -> chuỗi rỗng).
     */
    public boolean verify(JsonNode data, String signature) {
        if (!props.configured() || data == null || !data.isObject() || signature == null) return false;
        List<String> keys = new ArrayList<>(data.propertyNames());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            JsonNode v = data.get(k);
            String value = v == null || v.isNull() ? "" : v.isValueNode() ? v.asString() : mapper.writeValueAsString(v);
            if ("null".equals(value) || "undefined".equals(value)) value = "";
            if (!sb.isEmpty()) sb.append('&');
            sb.append(k).append('=').append(value);
        }
        String expected = Tokens.hmacSha256Hex(props.checksumKey(), sb.toString());
        return Tokens.constantTimeEquals(expected, signature.toLowerCase(Locale.ROOT));
    }
}
