package com.laundry.iot;

import com.laundry.iot.config.IotProvisioningProperties;
import com.laundry.iot.service.DeviceSecretService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the key-derivation contract shared with the ESP32 firmware. The
 * expected values were computed independently with Python's hmac module.
 */
class DeviceSecretServiceTest {

    private static final String DEVICE = "ESP-24A1B2C3D4E5";
    private static final String NONCE = "00112233445566778899aabbccddeeff";

    private DeviceSecretService secrets;

    @BeforeEach
    void setUp() {
        IotProvisioningProperties properties = new IotProvisioningProperties();
        properties.setDeviceSecretKey("0123456789abcdef0123456789abcdef");
        secrets = new DeviceSecretService(properties);
    }

    @Test
    void claimSecretMatchesReferenceVector() {
        assertThat(secrets.claimSecret(DEVICE, 1))
                .isEqualTo("d89ca8fc579b87257c50b0390c935803a911fb84fd51f2147624bf41201d47bc");
    }

    @Test
    void acceptsFirmwareProofAndRejectsTampering() {
        String proof = "f42daf2567b6c86249b156c694710a7d8aa49eb7e1580b6c9a93449210ff71d9";
        assertThat(secrets.verifyProof(DEVICE, 1, NONCE, proof)).isTrue();
        assertThat(secrets.verifyProof(DEVICE, 1, NONCE, proof.toUpperCase())).isTrue();
        assertThat(secrets.verifyProof(DEVICE, 2, NONCE, proof)).isFalse();      // re-enrolled
        assertThat(secrets.verifyProof(DEVICE, 1, "ff" + NONCE.substring(2), proof)).isFalse();
        assertThat(secrets.verifyProof(DEVICE, 1, NONCE, "zz" + proof.substring(2))).isFalse();
        assertThat(secrets.verifyProof(DEVICE, 1, NONCE, null)).isFalse();
    }

    @Test
    void mqttPasswordRotatesWithCredentialVersion() {
        assertThat(secrets.mqttPassword(DEVICE, 1)).isNotEqualTo(secrets.mqttPassword(DEVICE, 2));
        assertThat(secrets.mqttPassword(DEVICE, 1)).isEqualTo(secrets.mqttPassword(DEVICE, 1));
    }

    @Test
    void pairingCodeHashCheck() {
        String code = secrets.newPairingCode();
        assertThat(code).matches("[2-9A-HJ-NP-Z]{8}");
        assertThat(secrets.hashMatches(secrets.sha256Hex(code), code)).isTrue();
        assertThat(secrets.hashMatches(secrets.sha256Hex(code), code + "X")).isFalse();
    }

    @Test
    void refusesShortMasterKey() {
        IotProvisioningProperties weak = new IotProvisioningProperties();
        weak.setDeviceSecretKey("short");
        assertThatThrownBy(() -> new DeviceSecretService(weak).claimSecret(DEVICE, 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
