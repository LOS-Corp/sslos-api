package com.laundry.payment.gateway.payos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.payment.gateway.GatewayPaymentStatus;
import com.laundry.payment.gateway.PaymentGateway;
import com.laundry.payment.gateway.PaymentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
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

    private final PayOSProperties payos;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public PayOSGateway(PayOSProperties payos) {
        this.payos = payos;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public PaymentResult createPayment(UUID paymentId, BigDecimal amount, String returnUrl) {
        try {
            long orderCode = generateOrderCode(paymentId);
            long amountLong = amount.longValueExact();
            String description = "SSLOS #" + paymentId.toString().substring(0, 8);
            String cancelUrl = payos.cancelUrl().isBlank()
                    ? returnUrl + (returnUrl.contains("?") ? "&" : "?") + "cancelled=true"
                    : payos.cancelUrl();

            log.info("Creating PayOS payment: paymentId={}, orderCode={}, amount={}", paymentId, orderCode, amountLong);

            // If PayOS credentials are not yet configured, return simulated URL for development
            if (!payos.credentialsConfigured()) {
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

            String endpoint = payos.baseUrl().replaceAll("/+$", "") + "/v2/payment-requests";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-client-id", payos.clientId());
            headers.set("x-api-key", payos.apiKey());

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
    public Optional<GatewayPaymentStatus> getPaymentStatus(String transactionReference) {
        if (!payos.credentialsConfigured()) {
            return Optional.empty();
        }

        String endpoint = payos.baseUrl().replaceAll("/+$", "")
                + "/v2/payment-requests/" + transactionReference;
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-client-id", payos.clientId());
        headers.set("x-api-key", payos.apiKey());

        ResponseEntity<String> response = restTemplate.exchange(
                endpoint,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("PayOS status lookup failed with HTTP " + response.getStatusCode());
        }

        try {
            JsonNode responseJson = objectMapper.readTree(response.getBody());
            String code = responseJson.path("code").asText("");
            if (!"00".equals(code) && !"0".equals(code)) {
                throw new IllegalStateException("PayOS status lookup failed: "
                        + responseJson.path("desc").asText("unknown error"));
            }
            JsonNode data = responseJson.path("data");
            if (data.isMissingNode() || data.path("status").asText("").isBlank()
                    || !data.hasNonNull("orderCode") || !data.hasNonNull("amount")) {
                throw new IllegalStateException("PayOS returned an incomplete payment status");
            }
            return Optional.of(new GatewayPaymentStatus(
                    data.path("orderCode").asText(),
                    data.path("status").asText(),
                    new BigDecimal(data.path("amount").asText())));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse PayOS payment status", e);
        }
    }

    /**
     * Verify PayOS webhook data signature
     */
    public boolean verifyWebhookData(Map<String, Object> data, String signature) {
        if (!payos.canVerifyWebhooks() || signature == null || data == null) {
            return false;
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

            String computedSignature = hmacSha256Hex(sb.toString(), payos.checksumKey());
            return MessageDigest.isEqual(
                    computedSignature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                    signature.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
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
        return hmacSha256Hex(dataString, payos.checksumKey());
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
