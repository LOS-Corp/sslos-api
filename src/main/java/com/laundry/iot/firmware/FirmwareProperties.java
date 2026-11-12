package com.laundry.iot.firmware;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Flashing boards from the web. Prefix: {@code app.firmware}.
 */
@Component
@ConfigurationProperties(prefix = "app.firmware")
@Getter
@Setter
public class FirmwareProperties {

    /**
     * Command that runs esptool, e.g. {@code python,-m,esptool}. Empty = the esptool that
     * PlatformIO installs ({@code ~/.platformio/penv} python + {@code tool-esptoolpy/esptool.py}),
     * else {@code python -m esptool}.
     */
    private List<String> esptool = new ArrayList<>();

    /** Upload speed; CP2102/CH340 boards handle 460800. Lower it if flashing fails half way. */
    private int baudRate = 460800;

    /** Where the application image goes in the default partition table. */
    private String appOffset = "0x10000";

    /** esptool is killed after this long. */
    private int flashTimeoutSeconds = 180;

    /** After flashing, how long to wait for the board to come back with the new version. */
    private int rebootTimeoutSeconds = 45;

    /** Pause between closing the port and starting esptool (Windows releases the handle lazily). */
    private int releaseDelayMs = 500;
}
