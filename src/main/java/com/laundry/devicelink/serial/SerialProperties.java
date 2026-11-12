package com.laundry.devicelink.serial;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * USB serial link to the boards. Prefix: {@code app.serial}.
 */
@Component
@ConfigurationProperties(prefix = "app.serial")
@Getter
@Setter
public class SerialProperties {

    /** Open serial ports at all; false in tests and on machines without boards. */
    private boolean enabled = true;

    /**
     * Ports to use, e.g. {@code COM3,COM4} or {@code /dev/ttyUSB0}. Empty = every
     * port whose name or description matches {@link #portPattern}.
     */
    private List<String> ports = new ArrayList<>();

    /**
     * Auto-detection filter (case-insensitive regex) on "name description". The
     * default matches the USB-UART chips of ESP32 dev boards and skips Bluetooth
     * and built-in ports.
     */
    private String portPattern = "(?i).*(CH34|CH91|CP210|SLAB|FTDI|FT232|USB-SERIAL|USB SERIAL|UART|ttyUSB|ttyACM|usbserial).*";

    private int baudRate = 115200;

    /** How often ports are listed, so a board plugged in later is picked up. */
    private long scanIntervalMs = 3000;
}
