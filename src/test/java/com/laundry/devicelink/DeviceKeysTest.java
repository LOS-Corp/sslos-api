package com.laundry.devicelink;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceKeysTest {

    private static final String DEVICE = "ESP-24A1B2C3D4E5";

    @Test
    void acceptsTheBoardsSignatureOfItsOwnChallenge() {
        TestDeviceKey key = new TestDeviceKey();
        String nonce = DeviceKeys.newNonce();
        String message = DeviceKeys.challengeMessage(DEVICE, nonce);

        byte[] decoded = DeviceKeys.decodePublicKey(key.publicKeyBase64()).orElseThrow();

        assertThat(DeviceKeys.verify(decoded, message, key.sign(message))).isTrue();
    }

    @Test
    void refusesASignatureOfAnotherNonceBoardOrKey() {
        TestDeviceKey key = new TestDeviceKey();
        TestDeviceKey other = new TestDeviceKey();
        String nonce = DeviceKeys.newNonce();
        String message = DeviceKeys.challengeMessage(DEVICE, nonce);

        assertThat(DeviceKeys.verify(key.raw, DeviceKeys.challengeMessage(DEVICE, DeviceKeys.newNonce()), key.sign(message))).isFalse();
        assertThat(DeviceKeys.verify(key.raw, DeviceKeys.challengeMessage("ESP-000000000001", nonce), key.sign(message))).isFalse();
        assertThat(DeviceKeys.verify(key.raw, message, other.sign(message))).isFalse();
        assertThat(DeviceKeys.verify(key.raw, message, "not-base64!")).isFalse();
        assertThat(DeviceKeys.verify(key.raw, message, null)).isFalse();
    }

    @Test
    void rejectsKeysThatAreNotP256Points() {
        byte[] offCurve = new TestDeviceKey().raw.clone();
        offCurve[64] ^= 1;

        assertThat(DeviceKeys.decodePublicKey(Base64.getEncoder().encodeToString(offCurve))).isEmpty();
        assertThat(DeviceKeys.decodePublicKey(Base64.getEncoder().encodeToString(new byte[33]))).isEmpty();
        assertThat(DeviceKeys.decodePublicKey("%%%")).isEmpty();
        assertThat(DeviceKeys.decodePublicKey(null)).isEmpty();
    }

    @Test
    void fingerprintIsStableAndShortFormMatchesTheScreen() {
        TestDeviceKey key = new TestDeviceKey();
        String fingerprint = DeviceKeys.fingerprint(key.raw);

        assertThat(fingerprint).hasSize(64).matches("[0-9A-F]+").isEqualTo(DeviceKeys.fingerprint(key.raw.clone()));
        assertThat(DeviceKeys.shortFingerprint(fingerprint)).matches("[0-9A-F]{4}-[0-9A-F]{4}")
                .startsWith(fingerprint.substring(0, 4));
        assertThat(DeviceKeys.newNonce()).isNotEqualTo(DeviceKeys.newNonce());
    }
}
