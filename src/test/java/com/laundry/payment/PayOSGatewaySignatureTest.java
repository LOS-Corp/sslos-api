package com.laundry.payment;

import com.laundry.payment.gateway.payos.PayOSGateway;
import com.laundry.payment.gateway.payos.PayOSProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PayOSGatewaySignatureTest {
    @Test
    void webhookRequiresConfiguredKeyAndValidSignature() throws Exception {
        Map<String, Object> data = Map.of(
                "amount", 25000,
                "orderCode", 123456L,
                "description", "Laundry order");

        assertThat(new PayOSGateway(PayOSProperties.withChecksumKey("")).verifyWebhookData(data, "anything"))
                .as("no key configured → never trusted").isFalse();

        String checksumKey = "test-checksum-key";
        PayOSGateway gateway = new PayOSGateway(PayOSProperties.withChecksumKey(checksumKey));
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
