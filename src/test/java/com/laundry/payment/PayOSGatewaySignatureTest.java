package com.laundry.payment;

import com.laundry.payment.gateway.payos.PayOSGateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PayOSGatewaySignatureTest {
    @Test
    void webhookRequiresConfiguredKeyAndValidSignature() throws Exception {
        PayOSGateway gateway = new PayOSGateway();
        Map<String, Object> data = Map.of(
                "amount", 25000,
                "orderCode", 123456L,
                "description", "Laundry order");

        assertThat(gateway.verifyWebhookData(data, "anything")).isFalse();

        String checksumKey = "test-checksum-key";
        ReflectionTestUtils.setField(gateway, "checksumKey", checksumKey);
        String canonicalData = "amount=25000&description=Laundry order&orderCode=123456";

        assertThat(gateway.verifyWebhookData(data, hmacSha256(canonicalData, checksumKey))).isTrue();
        assertThat(gateway.verifyWebhookData(data, "invalid-signature")).isFalse();
        assertThat(gateway.verifyWebhookData(data, null)).isFalse();
    }

    private String hmacSha256(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }
}
