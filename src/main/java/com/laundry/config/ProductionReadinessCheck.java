package com.laundry.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Refuses to start the {@code prod} profile with development settings: weak or sample secrets,
 * the mock payment gateway, or a missing device master key (NFR-07, NFR-13, FR-PAY-01 fail-closed).
 */
@Component
@Profile("prod")
class ProductionReadinessCheck implements InitializingBean {

    private static final List<String> SAMPLE_MARKERS = List.of("change", "dev", "test", "local", "example", "secret");

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.payment.gateway}")
    private String paymentGateway;

    @Value("${app.payos.checksum-key:}")
    private String payosChecksumKey;

    @Value("${app.iot.device-secret-key:}")
    private String deviceSecretKey;

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        if (jwtSecret.length() < 48 || looksLikeSample(jwtSecret)) {
            problems.add("JWT_SECRET must be a random value of at least 48 characters");
        }
        if (!"payos".equalsIgnoreCase(paymentGateway)) {
            problems.add("PAYMENT_GATEWAY must be payos in production");
        }
        if (payosChecksumKey.isBlank()) {
            problems.add("PAYOS_CHECKSUM_KEY is required to verify webhooks");
        }
        if (deviceSecretKey.length() < 32) {
            problems.add("IOT_DEVICE_SECRET_KEY must be at least 32 characters");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Production configuration rejected: " + String.join("; ", problems));
        }
    }

    private static boolean looksLikeSample(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return SAMPLE_MARKERS.stream().anyMatch(lower::contains);
    }
}
