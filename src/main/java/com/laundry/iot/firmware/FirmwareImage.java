package com.laundry.iot.firmware;

import com.laundry.machine.entity.MachineType;

import java.nio.charset.StandardCharsets;

/**
 * An uploaded ESP32 application image ({@code .pio/build/esp32dev/firmware.bin}),
 * checked before anything touches a board:
 * <ul>
 *   <li>it is an app image (magic {@code 0xE9} and the {@code esp_app_desc_t} magic at 0x20),
 *       not a bootloader, partition table or merged image, so writing it at 0x10000 is right;</li>
 *   <li>it fits the default app partition;</li>
 *   <li>it carries the LaundryOS marker {@code LAUNDRYOS-FW|<type>|<version>} (the
 *       {@code FIRMWARE_ID} constant in each firmware's main.cpp), so a washer image is
 *       never flashed onto a dryer.</li>
 * </ul>
 */
public record FirmwareImage(MachineType deviceType, String version, byte[] bytes) {

    /** app0 in the default 4 MB partition table (PlatformIO esp32dev default.csv: 0x10000, size 0x140000). */
    public static final int MAX_SIZE = 0x140000;
    private static final int IMAGE_MAGIC = 0xE9;
    private static final byte[] APP_DESC_MAGIC = {0x32, 0x54, (byte) 0xCD, (byte) 0xAB};  // 0xABCD5432 LE
    private static final int APP_DESC_OFFSET = 0x20;
    private static final byte[] MARKER = "LAUNDRYOS-FW|".getBytes(StandardCharsets.US_ASCII);
    private static final int MARKER_MAX = 64;

    public static FirmwareImage parse(byte[] bytes) {
        if (bytes == null || bytes.length < 256) {
            throw new InvalidFirmwareException("The file is empty or truncated");
        }
        if ((bytes[0] & 0xFF) != IMAGE_MAGIC || !matchesAt(bytes, APP_DESC_OFFSET, APP_DESC_MAGIC)) {
            throw new InvalidFirmwareException(
                    "Not an ESP32 application image; upload .pio/build/esp32dev/firmware.bin, "
                            + "not bootloader.bin, partitions.bin or a merged image");
        }
        if (bytes.length > MAX_SIZE) {
            throw new InvalidFirmwareException("The image is " + bytes.length + " bytes; the app partition holds "
                    + MAX_SIZE);
        }
        int at = indexOf(bytes, MARKER);
        if (at < 0) {
            throw new InvalidFirmwareException(
                    "No LaundryOS firmware marker: the image is from another project or an older firmware");
        }
        int end = at;
        while (end < bytes.length && end - at < MARKER_MAX && bytes[end] != 0) end++;
        String[] parts = new String(bytes, at, end - at, StandardCharsets.US_ASCII).split("\\|");
        if (parts.length != 3 || parts[2].isBlank()) {
            throw new InvalidFirmwareException("Unreadable LaundryOS firmware marker");
        }
        MachineType type;
        try {
            type = MachineType.valueOf(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new InvalidFirmwareException("Unknown firmware type " + parts[1]);
        }
        return new FirmwareImage(type, parts[2], bytes);
    }

    private static boolean matchesAt(byte[] data, int offset, byte[] pattern) {
        if (data.length < offset + pattern.length) return false;
        for (int i = 0; i < pattern.length; i++) {
            if (data[offset + i] != pattern[i]) return false;
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** The uploaded file cannot be flashed. */
    public static class InvalidFirmwareException extends RuntimeException {
        public InvalidFirmwareException(String message) {
            super(message);
        }
    }
}
