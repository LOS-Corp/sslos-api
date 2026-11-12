package com.laundry.devicelink;

import java.util.Arrays;
import java.util.Optional;

/**
 * Device-to-backend channels. On the USB serial link a frame is
 * {@code @<channel> <json>}, e.g. {@code @command/ack {"correlationId":"..."}}.
 */
public enum DeviceChannel {
    STATUS("status"),
    TELEMETRY("telemetry"),
    HEARTBEAT("heartbeat"),
    EVENT("event"),
    COMMAND_ACK("command/ack"),
    /** QR check-in and physical START authorization (AUTH_ACK, START_REQUEST, STARTED, ...). */
    AUTH("auth");

    private final String path;

    DeviceChannel(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static Optional<DeviceChannel> fromPath(String path) {
        return Arrays.stream(values())
                .filter(channel -> channel.path.equals(path))
                .findFirst();
    }
}
