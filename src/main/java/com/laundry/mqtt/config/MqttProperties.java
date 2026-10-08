package com.laundry.mqtt.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration properties for the MQTT connection.
 *
 * All values come from environment variables via application.properties.
 * No credentials are ever hardcoded here.
 *
 * Prefix: app.mqtt
 */
@Component
@ConfigurationProperties(prefix = "app.mqtt")
@Validated
@Getter
@Setter
public class MqttProperties {

    /** EMQX Cloud broker hostname. e.g., "abc123.emqxsl.com" */
    private String brokerHost = "localhost";

    /** MQTT over TLS port (8883) or plain MQTT port (1883). */
    private int brokerPort = 8883;

    /** MQTT username from EMQX Cloud credentials. */
    private String username = "";

    /** MQTT password from EMQX Cloud credentials. */
    private String password = "";

    /**
     * Client ID must be unique per connected client and not dependent on machine.
     * Default format: laundryos-backend-{instanceId}
     */
    private String clientId = "laundryos-backend-" + java.util.UUID.randomUUID().toString().substring(0, 8);

    /** Whether to use TLS (ssl:// vs tcp://). Always true for EMQX Cloud. */
    private boolean useTls = true;

    /** Connection timeout in seconds. */
    private int connectionTimeoutSeconds = 30;

    /** Keep-alive interval in seconds. */
    private int keepAliveIntervalSeconds = 60;

    /** Whether to start a clean session (true = no persistent session on broker). */
    private boolean cleanSession = true;

    /**
     * QoS level for publishing commands to devices.
     * 0 = at most once, 1 = at least once (recommended), 2 = exactly once.
     */
    private int commandQos = 1;

    /**
     * Builds the full broker URI for Paho MQTT client.
     * Uses ssl:// for TLS connections, tcp:// for plain.
     */
    public String buildBrokerUri() {
        String scheme = useTls ? "ssl" : "tcp";
        return String.format("%s://%s:%d", scheme, brokerHost, brokerPort);
    }
}
