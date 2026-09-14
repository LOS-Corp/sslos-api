package com.laundry.payment.gateway.payos;

import com.laundry.payment.gateway.PaymentGateway;
import com.laundry.payment.gateway.PaymentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Base64;
import java.util.UUID;

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

    @Value("${app.payos.base-url:https://api.payos.vn}")
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
            log.info("Creating PayOS payment: paymentId={}, amount={}", paymentId, amount);

            // Generate order code from payment ID (PayOS requires unique numeric order code)
            long orderCode = generateOrderCode(paymentId);

            // Build payment request body
            String requestBody = String.format("""
                {
                    "orderCode": %d,
                    "amount": %d,
                    "description": "SSLOS Payment #%s",
                    "returnUrl": "%s",
                    "cancelUrl": "%s?cancelled=true"
                }
                """, 
                orderCode, 
                amount.longValue(), 
                paymentId.toString().substring(0, 8),
                returnUrl,
                returnUrl
            );

            // Create signature
            String signature = createSignature(orderCode, amount.longValue(), paymentId.toString().substring(0, 8));

            // Call PayOS API
            String url = baseUrl + "/v2/payment-requests";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Client-Id", clientId);
            headers.set("X-Api-Key", apiKey);

            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
            
            log.info("Calling PayOS API: {}", url);
            log.info("Request body: {}", requestBody);

            ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                request,
                String.class
            );

            log.info("PayOS API response: {}", response.getBody());

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode responseJson = objectMapper.readTree(response.getBody());
                
                int code = responseJson.path("code").asInt(-1);
                String desc = responseJson.path("desc").asText("Unknown error");
                
                if (code == 0) {
                    JsonNode data = responseJson.path("data");
                    String checkoutUrl = data.path("checkoutUrl").asText("");
                    String qrCode = data.path("qrCode").asText("");
                    
                    log.info("PayOS payment created successfully: orderCode={}, checkoutUrl={}", orderCode, checkoutUrl);
                    
                    // Return checkout URL or QR code
                    if (!checkoutUrl.isEmpty()) {
                        return PaymentResult.success(checkoutUrl, String.valueOf(orderCode));
                    } else if (!qrCode.isEmpty()) {
                        return PaymentResult.success(qrCode, String.valueOf(orderCode), true);
                    } else {
                        return PaymentResult.success("https://payos.vn/" + orderCode, String.valueOf(orderCode));
                    }
                } else {
                    log.error("PayOS API error: code={}, desc={}", code, desc);
                    return PaymentResult.failure("PayOS error: " + desc);
                }
            }

            return PaymentResult.failure("Failed to create PayOS payment: HTTP " + response.getStatusCode());

        } catch (Exception e) {
            log.error("Error creating PayOS payment", e);
            return PaymentResult.failure("Error: " + e.getMessage());
        }
    }

    @Override
    public boolean verifyCallback(String transactionId, String signature) {
        try {
            log.info("Verifying PayOS callback: transactionId={}", transactionId);
            
            // Verify HMAC signature from PayOS
            // Format: orderCode|amount|description|createdAt|updatedAt|status
            String expectedSignature = createHmacSignature(transactionId);
            
            return expectedSignature.equals(signature);
        } catch (Exception e) {
            log.error("Error verifying PayOS callback", e);
            return false;
        }
    }

    @Override
    public String getGatewayName() {
        return "PayOS";
    }

    /**
     * Generate unique order code from payment ID
     */
    private long generateOrderCode(UUID paymentId) {
        String uuidDigits = paymentId.toString().replace("-", "");
        long orderCode = Math.abs(uuidDigits.hashCode() % 100000000000L);
        if (orderCode < 10000000000L) {
            orderCode += 10000000000L;
        }
        return orderCode;
    }

    /**
     * Create HMAC-SHA256 signature for PayOS API
     */
    private String createSignature(long orderCode, long amount, String description) {
        try {
            String dataString = String.format("%d|%d|%s", orderCode, amount, description);
            
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                checksumKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            );
            mac.init(secretKeySpec);
            
            byte[] hmacBytes = mac.doFinal(dataString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error creating signature", e);
            return "";
        }
    }

    /**
     * Create HMAC signature for webhook verification
     */
    private String createHmacSignature(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(
                checksumKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            );
            mac.init(secretKeySpec);
            
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Error creating HMAC signature", e);
            return "";
        }
    }
}
