package com.laundry.payment.gateway.payos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.payment.gateway.PaymentGateway;
import com.laundry.payment.gateway.PaymentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * PayOS Payment Gateway Implementation
 * 
 * PayOS is a Vietnamese payment gateway that supports VietQR payment.
 * API Docs: https://docs.payos.vn/
 * 
 * Configuration:
 * - app.payment.gateway=payos
 * - app.payos.client-id: PayOS Client ID
 * - app.payos.api-key: PayOS API Key
 * - app.payos.checksum-key: PayOS Checksum Key
 * - app.payos.base-url: https://api-merchant.payos.vn
 */
@Component
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "payos")
@Slf4j
public class PayOSGateway implements PaymentGateway {

    @Value("${app.payos.client-id:}")
    private String clientId;

    @Value("${app.payos.api-key:}")
    private String apiKey;

    @Value("${app.payos.checksum-key:}")
    private String checksumKey;

    @Value("${app.payos.base-url:https://api-merchant.payos.vn}")
    private String baseUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PayOSGateway() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public PaymentResult createPayment(UUID paymentId, BigDecimal amount, String returnUrl) {
        try {
            long orderCode = generateOrderCode(paymentId);
            long amountLong = amount.longValue();
            String description = "SSLOS #" + paymentId.toString().substring(0, 8);
            String cancelUrl = returnUrl + (returnUrl.contains("?") ? "&" : "?") + "cancelled=true";

            log.info("Creating PayOS payment: paymentId={}, orderCode={}, amount={}", paymentId, orderCode, amountLong);

            // If PayOS credentials are not yet configured, return simulated URL for development
            if (clientId == null || clientId.isBlank() || apiKey == null || apiKey.isBlank() || checksumKey == null || checksumKey.isBlank()) {
                log.warn("PayOS credentials not configured (PAYOS_CLIENT_ID, PAYOS_API_KEY, PAYOS_CHECKSUM_KEY). Using simulated PayOS checkout URL.");
                String simulatedUrl = "https://pay.payos.vn/web/test-" + orderCode;
                String simulatedQr = "00020101021238580010A000000727012600069704220112" + orderCode;
                return PaymentResult.builder()
                        .success(true)
                        .paymentUrl(simulatedUrl)
                        .qrCode(simulatedQr)
                        .transactionReference(String.valueOf(orderCode))
                        .build();
            }

            // Create signature for PayOS v2:
            // Fields sorted alphabetically: amount, cancelUrl, description, orderCode, returnUrl
            String signature = createSignature(orderCode, amountLong, description, cancelUrl, returnUrl);

            Map<String, Object> requestBodyMap = new LinkedHashMap<>();
            requestBodyMap.put("orderCode", orderCode);
            requestBodyMap.put("amount", amountLong);
            requestBodyMap.put("description", description);
            requestBodyMap.put("cancelUrl", cancelUrl);
            requestBodyMap.put("returnUrl", returnUrl);
            requestBodyMap.put("signature", signature);

            String requestJson = objectMapper.writeValueAsString(requestBodyMap);

            String endpoint = baseUrl.replaceAll("/+$", "") + "/v2/payment-requests";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-client-id", clientId);
            headers.set("x-api-key", apiKey);

            HttpEntity<String> request = new HttpEntity<>(requestJson, headers);

            log.info("Sending PayOS payment request to: {}", endpoint);
            ResponseEntity<String> response = restTemplate.exchange(
                    endpoint,
                    HttpMethod.POST,
                    request,
                    String.class
            );

            log.info("PayOS API HTTP status: {}", response.getStatusCode());

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode responseJson = objectMapper.readTree(response.getBody());
                String codeStr = responseJson.path("code").asText("");
                int code = responseJson.path("code").asInt(-1);
                String desc = responseJson.path("desc").asText("");

                if (code == 0 || "00".equals(codeStr) || "0".equals(codeStr)) {
                    JsonNode data = responseJson.path("data");
                    String checkoutUrl = data.path("checkoutUrl").asText("");
                    String qrCode = data.path("qrCode").asText("");

                    log.info("PayOS payment created successfully: orderCode={}, checkoutUrl={}", orderCode, checkoutUrl);

                    return PaymentResult.builder()
                            .success(true)
                            .paymentUrl(checkoutUrl.isEmpty() ? "https://pay.payos.vn/web/" + orderCode : checkoutUrl)
                            .qrCode(qrCode)
                            .transactionReference(String.valueOf(orderCode))
                            .build();
                } else {
                    log.error("PayOS returned error: code={}, desc={}", codeStr, desc);
                    return PaymentResult.failure("PayOS error: " + desc);
                }
            }

            return PaymentResult.failure("PayOS request failed with HTTP " + response.getStatusCode());

        } catch (Exception e) {
            log.error("Error creating PayOS payment", e);
            return PaymentResult.failure("PayOS error: " + e.getMessage());
        }
    }

    @Override
    public boolean verifyCallback(String transactionId, String signature) {
        if (checksumKey == null || checksumKey.isBlank()) {
            return true;
        }
        String expectedSignature = hmacSha256Hex(transactionId, checksumKey);
        return expectedSignature.equalsIgnoreCase(signature);
    }

    /**
     * Verify PayOS webhook data signature
     */
    public boolean verifyWebhookData(Map<String, Object> data, String signature) {
        if (checksumKey == null || checksumKey.isBlank() || signature == null || data == null) {
            return true;
        }
        try {
            List<String> sortedKeys = new ArrayList<>(data.keySet());
            Collections.sort(sortedKeys);

            StringBuilder sb = new StringBuilder();
            for (String key : sortedKeys) {
                Object val = data.get(key);
                if (val != null && !(val instanceof Map) && !(val instanceof List)) {
                    if (sb.length() > 0) {
                        sb.append("&");
                    }
                    sb.append(key).append("=").append(val);
                }
            }

            String computedSignature = hmacSha256Hex(sb.toString(), checksumKey);
            return computedSignature.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("Error verifying PayOS webhook signature", e);
            return false;
        }
    }

    @Override
    public String getGatewayName() {
        return "PayOS";
    }

    /**
     * Generate unique orderCode from payment ID and timestamp (under 9007199254740991)
     */
    public long generateOrderCode(UUID paymentId) {
        long timestampSeconds = System.currentTimeMillis() / 1000L;
        int randomPart = Math.abs(paymentId.hashCode() % 100000);
        return timestampSeconds * 100000L + randomPart;
    }

    /**
     * Create HMAC-SHA256 signature formatted as lowercase hex string for create payment request
     * String format: amount=%d&cancelUrl=%s&description=%s&orderCode=%d&returnUrl=%s
     */
    private String createSignature(long orderCode, long amount, String description, String cancelUrl, String returnUrl) {
        String dataString = String.format("amount=%d&cancelUrl=%s&description=%s&orderCode=%d&returnUrl=%s",
                amount, cancelUrl, description, orderCode, returnUrl);
        return hmacSha256Hex(dataString, checksumKey);
    }

    /**
     * Compute HMAC-SHA256 and return lowercase hex string
     */
    private String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                    key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            );
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error creating HMAC signature", e);
            return "";
        }
    }
}
