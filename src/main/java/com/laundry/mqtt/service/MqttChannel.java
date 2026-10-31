package com.laundry.mqtt.service;

import java.util.Arrays;
import java.util.Optional;

/**
 * Device-to-backend channels, i.e. the topic suffix after
 * {@code laundry/washer/{branchId}/{machineId}/}.
 */
public enum MqttChannel {

    STATUS("status"),
    TELEMETRY("telemetry"),
    HEARTBEAT("heartbeat"),
    EVENT("event"),
    COMMAND_ACK("command/ack"),
    /** QR check-in and physical START authorization (AUTH_ACK, START_REQUEST, STARTED, ...). */
    AUTH("auth");

    private final String path;

    MqttChannel(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static Optional<MqttChannel> fromPath(String path) {
        return Arrays.stream(values())
                .filter(channel -> channel.path.equals(path))
                .findFirst();
    }
}
