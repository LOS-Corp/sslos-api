package com.laundry.device.link;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.mqtt.*: phiên của backend trên EMQX của cửa hàng. */
@ConfigurationProperties(prefix = "app.mqtt")
public record MqttProperties(
        boolean enabled,
        String host,
        int port,
        String username,
        String password,
        int deviceTlsPort,
        long keepaliveMs,
        int timeDivisor
) {
    public String brokerUrl() {
        return "tcp://" + host + ":" + port;
    }

    public int effectiveTimeDivisor() {
        return Math.max(1, Math.min(60, timeDivisor));
    }
}
