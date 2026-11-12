package com.laundry.iot.firmware;

import com.laundry.machine.entity.MachineType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirmwareImageTest {

    /** A minimal app image: 0xE9 header, esp_app_desc magic at 0x20, the marker somewhere in rodata. */
    static byte[] image(String marker, int size) {
        byte[] bytes = new byte[size];
        bytes[0] = (byte) 0xE9;
        bytes[0x20] = 0x32;
        bytes[0x21] = 0x54;
        bytes[0x22] = (byte) 0xCD;
        bytes[0x23] = (byte) 0xAB;
        if (marker != null) {
            byte[] text = marker.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(text, 0, bytes, 300, text.length);   // NUL-terminated by the zero after it
        }
        return bytes;
    }

    @Test
    void readsTheTypeAndVersionFromTheMarker() {
        FirmwareImage image = FirmwareImage.parse(image("LAUNDRYOS-FW|WASHER|usb-4.1.0", 4096));

        assertThat(image.deviceType()).isEqualTo(MachineType.WASHER);
        assertThat(image.version()).isEqualTo("usb-4.1.0");
    }

    @Test
    void refusesABootloaderOrMergedImage() {
        byte[] bootloader = image("LAUNDRYOS-FW|WASHER|usb-4.1.0", 4096);
        bootloader[0x20] = (byte) 0xFF;   // no esp_app_desc: bootloader.bin / merged factory image

        assertThatThrownBy(() -> FirmwareImage.parse(bootloader))
                .isInstanceOf(FirmwareImage.InvalidFirmwareException.class)
                .hasMessageContaining("firmware.bin");
    }

    @Test
    void refusesAnImageWithoutTheLaundryOsMarker() {
        assertThatThrownBy(() -> FirmwareImage.parse(image(null, 4096)))
                .hasMessageContaining("marker");
        assertThatThrownBy(() -> FirmwareImage.parse(image("LAUNDRYOS-FW|TOASTER|1.0", 4096)))
                .hasMessageContaining("TOASTER");
    }

    @Test
    void refusesAnImageLargerThanTheAppPartition() {
        assertThatThrownBy(() -> FirmwareImage.parse(image("LAUNDRYOS-FW|DRYER|d-1", FirmwareImage.MAX_SIZE + 1)))
                .hasMessageContaining("app partition");
    }
}
