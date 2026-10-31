package com.laundry.iot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Device provisioning settings. Prefix: {@code app.iot}.
 */
@Component
@ConfigurationProperties(prefix = "app.iot")
@Getter
@Setter
public class IotProvisioningProperties {

    /**
     * Server-side master key (≥ 32 chars). Per-device claim secrets and MQTT
     * passwords are HMAC-derived from it, so nothing secret is stored per row.
     * Rotating it invalidates every enrolled device.
     */
    private String deviceSecretKey = "";

    /** Lifetime of a pairing session opened by scanning the device QR. */
    private int pairingTtlSeconds = 600;

    /** Lifetime of an activation challenge nonce. */
    private int challengeTtlSeconds = 60;

    /** Wrong pairing codes tolerated before the device is LOCKED. */
    private int maxPairingAttempts = 5;

    /** How long the device waits for the technician to press START during SELF_TEST. */
    private int selfTestButtonTimeoutSeconds = 20;

    /** MQTT endpoint handed to the device after activation. */
    private String deviceMqttHost = "";
    private int deviceMqttPort = 8883;
}
