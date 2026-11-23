package com.laundry.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.payos")
public record PayOsProperties(String baseUrl, String clientId, String apiKey, String checksumKey, String returnUrl,
                             String cancelUrl) {

    public boolean configured() {
        return notBlank(clientId) && notBlank(apiKey) && notBlank(checksumKey);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
