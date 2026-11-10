package com.laundry.iot;

import com.laundry.iot.config.IotProvisioningProperties;
import com.laundry.iot.service.DeviceSecretService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the pairing-code contract shared with the ESP32 firmware. The expected
 * proof was computed independently with Python's hmac module.
 */
class DeviceSecretServiceTest {

    private static final String DEVICE = "ESP-24A1B2C3D4E5";
    private static final String NONCE = "00112233445566778899aabbccddeeff";
    /** The 6-digit code the board shows; its ASCII bytes are the HMAC key. */
    private static final String SETUP_KEY = "123456";

    private DeviceSecretService secrets;

    @BeforeEach
    void setUp() {
        IotProvisioningProperties properties = new IotProvisioningProperties();
        properties.setDeviceSecretKey("0123456789abcdef0123456789abcdef");
        secrets = new DeviceSecretService(properties);
    }

    @Test
    void acceptsFirmwareProofAndRejectsTampering() {
        String proof = "3a2dae89b5efd253f76cd40a59c1bd1f86f6eb15a6775ae606c844fe83d53d75";
        assertThat(secrets.verifyProof(DEVICE, SETUP_KEY, NONCE, proof)).isTrue();
        assertThat(secrets.verifyProof(DEVICE, SETUP_KEY, NONCE, proof.toUpperCase())).isTrue();
        assertThat(secrets.verifyProof(DEVICE, "123457", NONCE, proof)).isFalse(); // other key
        assertThat(secrets.verifyProof("ESP-000000000000", SETUP_KEY, NONCE, proof)).isFalse();      // other board
        assertThat(secrets.verifyProof(DEVICE, SETUP_KEY, "ff" + NONCE.substring(2), proof)).isFalse(); // replay
        assertThat(secrets.verifyProof(DEVICE, SETUP_KEY, NONCE, "zz" + proof.substring(2))).isFalse();
        assertThat(secrets.verifyProof(DEVICE, SETUP_KEY, NONCE, null)).isFalse();
        assertThat(secrets.verifyProof(DEVICE, null, NONCE, proof)).isFalse();                      // no pairing
    }

    @Test
    void normalizesAndValidatesTypedSetupKeys() {
        assertThat(DeviceSecretService.normalizeSetupKey(" 123 456 ")).isEqualTo(SETUP_KEY);
        assertThat(DeviceSecretService.normalizeSetupKey("123-456")).isEqualTo(SETUP_KEY);
        assertThat(secrets.isValidSetupKey(SETUP_KEY)).isTrue();
        assertThat(secrets.isValidSetupKey("000000")).isTrue();
        assertThat(secrets.isValidSetupKey("12345")).isFalse();     // too short
        assertThat(secrets.isValidSetupKey("1234567")).isFalse();   // too long
        assertThat(secrets.isValidSetupKey("12345A")).isFalse();    // not a digit
    }

    @Test
    void setupKeyRoundTripsThroughEncryption() {
        String sealed = secrets.encryptSetupKey(SETUP_KEY);
        assertThat(sealed).doesNotContain(SETUP_KEY);
        assertThat(secrets.encryptSetupKey(SETUP_KEY)).isNotEqualTo(sealed);   // random IV
        assertThat(secrets.decryptSetupKey(sealed)).isEqualTo(SETUP_KEY);
    }

    @Test
    void sealedKeyIsUnreadableUnderAnotherMasterKeyOrWhenTampered() {
        String sealed = secrets.encryptSetupKey(SETUP_KEY);
        IotProvisioningProperties other = new IotProvisioningProperties();
        other.setDeviceSecretKey("fedcba9876543210fedcba9876543210");
        assertThat(new DeviceSecretService(other).decryptSetupKey(sealed)).isNull();

        char last = sealed.charAt(sealed.length() - 2);
        String tampered = sealed.substring(0, sealed.length() - 2) + (last == 'A' ? 'B' : 'A') + sealed.charAt(sealed.length() - 1);
        assertThat(secrets.decryptSetupKey(tampered)).isNull();
        assertThat(secrets.decryptSetupKey(null)).isNull();
        assertThat(secrets.decryptSetupKey("not base64!")).isNull();
    }

    @Test
    void mqttPasswordRotatesWithCredentialVersion() {
        assertThat(secrets.mqttPassword(DEVICE, 1)).isNotEqualTo(secrets.mqttPassword(DEVICE, 2));
        assertThat(secrets.mqttPassword(DEVICE, 1)).isEqualTo(secrets.mqttPassword(DEVICE, 1));
    }

    @Test
    void refusesShortMasterKey() {
        IotProvisioningProperties weak = new IotProvisioningProperties();
        weak.setDeviceSecretKey("short");
        assertThatThrownBy(() -> new DeviceSecretService(weak).encryptSetupKey(SETUP_KEY))
                .isInstanceOf(IllegalStateException.class);
    }
}
