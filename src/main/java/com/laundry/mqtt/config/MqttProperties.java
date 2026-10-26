package com.laundry.mqtt.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

/**
 * Backend MQTT connection settings, bound from {@code app.mqtt.*}.
 * Credentials come from environment variables and are never hardcoded.
 */
@Component
@ConfigurationProperties(prefix = "app.mqtt")
@Validated
@Getter
@Setter
public class MqttProperties {

    @NotBlank
    private String brokerHost = "localhost";

    @Min(1)
    @Max(65535)
    private int brokerPort = 8883;

    private String username = "";

    private String password = "";

    /** Must be unique per backend instance, otherwise the broker kicks the older session. */
    @NotBlank
    private String clientId = "laundryos-backend-" + UUID.randomUUID().toString().substring(0, 8);

    /** {@code ssl://} when true, {@code tcp://} otherwise. Always true for EMQX Cloud. */
    private boolean useTls = true;

    private int connectionTimeoutSeconds = 30;

    private int keepAliveIntervalSeconds = 60;

    private boolean cleanSession = true;

    /** QoS used when publishing commands to devices. */
    @Min(0)
    @Max(2)
    private int commandQos = 1;

    public String brokerUri() {
        return (useTls ? "ssl" : "tcp") + "://" + brokerHost + ":" + brokerPort;
    }
}
