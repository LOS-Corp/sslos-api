package com.laundry.iot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * EMQX management API used to create per-device users and ACL rules.
 * Prefix: {@code app.emqx}.
 */
@Component
@ConfigurationProperties(prefix = "app.emqx")
@Getter
@Setter
public class EmqxAdminProperties {

    public enum Mode {
        /** Backend manages users/ACL through the EMQX v5 REST API. */
        API,
        /**
         * Broker has no usable management API (e.g. some EMQX Cloud Serverless
         * plans): the admin copies the credential from
         * {@code GET /api/admin/iot-devices/{deviceId}/mqtt-credential} into the
         * EMQX console. Revocation must also be done in the console.
         */
        MANUAL
    }

    private Mode provisioningMode = Mode.MANUAL;

    /** e.g. {@code https://xxxx.emqxsl.com:8443/api/v5} or {@code http://localhost:18083/api/v5}. */
    private String apiBaseUrl = "";

    /** API key / App ID (HTTP Basic username). */
    private String apiKey = "";

    /** API secret / App Secret (HTTP Basic password). */
    private String apiSecret = "";

    /** Authenticator id of the built-in password database. */
    private String authenticatorId = "password_based:built_in_database";
}
