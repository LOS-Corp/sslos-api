package com.laundry.devicelink;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Board identifiers: {@code ESP-} and the 12 hex digits of the ESP32's MAC, e.g. {@code ESP-3076F591F738}.
 * The same id is the board's MQTT client id, its broker username and its topic segment, so it is checked
 * in one place.
 */
public final class DeviceIds {

    /** For bean validation ({@code @Pattern}); keep in step with {@code emqx/acl.conf}. */
    public static final String REGEX = "^ESP-[0-9A-F]{12}$";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    private DeviceIds() {
    }

    /** Trimmed and upper-cased; an empty string for {@code null}. */
    public static String normalize(String deviceId) {
        return deviceId == null ? "" : deviceId.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String deviceId) {
        return deviceId != null && PATTERN.matcher(deviceId).matches();
    }
}
