package com.laundry.payment.gateway.payos;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * payOS settings ({@code app.payos.*}), bound once instead of read by each class. Missing values are
 * empty strings, so callers check {@link #credentialsConfigured()} / {@link #canVerifyWebhooks()}.
 */
@ConfigurationProperties(prefix = "app.payos")
public record PayOSProperties(
        String clientId,
        String apiKey,
        /* HMAC key of request signatures and webhooks. */
        String checksumKey,
        @DefaultValue("https://api-merchant.payos.vn") String baseUrl,
        /* Where payOS sends the customer after paying / cancelling; blank = derived from the payment. */
        String returnUrl,
        String cancelUrl) {

    public PayOSProperties {
        clientId = blankIfNull(clientId);
        apiKey = blankIfNull(apiKey);
        checksumKey = blankIfNull(checksumKey);
        returnUrl = blankIfNull(returnUrl);
        cancelUrl = blankIfNull(cancelUrl);
    }

    /** Settings with only a checksum key, for tests of signature handling. */
    public static PayOSProperties withChecksumKey(String checksumKey) {
        return new PayOSProperties("", "", checksumKey, "https://api-merchant.payos.vn", "", "");
    }

    public boolean credentialsConfigured() {
        return !clientId.isBlank() && !apiKey.isBlank() && !checksumKey.isBlank();
    }

    public boolean canVerifyWebhooks() {
        return !checksumKey.isBlank();
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value.trim();
    }
}
