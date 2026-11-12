package com.laundry.devicelink;

import java.util.Optional;

/**
 * One protocol line on the serial link: {@code @<channel> <json>}.
 *
 * <p>Board → backend channels: {@code hello} and the {@link DeviceChannel} paths.
 * Backend → board channels: {@code link} (binding state + keepalive) and {@code cmd}.
 * Any line that does not start with {@code @} is the board's debug log.
 */
public record DeviceFrame(String channel, String payload) {

    public static final char MARKER = '@';
    public static final String HELLO = "hello";
    public static final String LINK = "link";
    public static final String COMMAND = "cmd";

    /** Longest line accepted from a board; longer input is dropped. */
    public static final int MAX_LINE = 4096;

    /** Empty for a log line or a malformed frame. */
    public static Optional<DeviceFrame> parse(String line) {
        if (line == null) return Optional.empty();
        String trimmed = line.strip();
        if (trimmed.length() < 2 || trimmed.charAt(0) != MARKER) return Optional.empty();
        int space = trimmed.indexOf(' ');
        String channel = (space < 0 ? trimmed.substring(1) : trimmed.substring(1, space)).trim();
        String payload = space < 0 ? "{}" : trimmed.substring(space + 1).trim();
        if (channel.isEmpty() || payload.isEmpty() || payload.charAt(0) != '{') return Optional.empty();
        return Optional.of(new DeviceFrame(channel, payload));
    }

    /** The line to write, without the trailing newline. */
    public String format() {
        return MARKER + channel + " " + payload;
    }
}
