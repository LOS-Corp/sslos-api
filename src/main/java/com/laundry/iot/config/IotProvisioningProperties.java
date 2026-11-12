package com.laundry.iot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Device commissioning settings. Prefix: {@code app.iot}.
 */
@Component
@ConfigurationProperties(prefix = "app.iot")
@Getter
@Setter
public class IotProvisioningProperties {

    /** How long the device waits for the technician to press START during SELF_TEST. */
    private int selfTestButtonTimeoutSeconds = 20;
}
