package com.laundry.iot.service;

import com.laundry.iot.config.IotProvisioningProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Key derivation and constant-time checks for device provisioning.
 *
 * <pre>
 * claimSecret  = HMAC(deviceSecretKey, "claim:" + deviceId + ":" + enrollmentVersion)    (hex, 64 chars)
 * mqttPassword = HMAC(deviceSecretKey, "mqtt:"  + deviceId + ":" + credentialVersion)    (base64url)
 * proof        = HMAC(claimSecret, deviceId + ":" + nonce)                               (hex, sent by ESP32)
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class DeviceSecretService {

    /** No 0/O/1/I so the code can be read off a label without ambiguity. */
    private static final char[] PAIRING_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final HexFormat HEX = HexFormat.of();

    private final IotProvisioningProperties properties;
    private final SecureRandom random = new SecureRandom();

    public String claimSecret(String deviceId, int enrollmentVersion) {
        return HEX.formatHex(hmac(masterKey(), "claim:" + deviceId + ":" + enrollmentVersion));
    }

    public String mqttPassword(String deviceId, int credentialVersion) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(masterKey(), "mqtt:" + deviceId + ":" + credentialVersion));
    }

    /** Verify the ESP32's HMAC proof of possession of its claim secret. */
    public boolean verifyProof(String deviceId, int enrollmentVersion, String nonce, String proofHex) {
        if (proofHex == null || proofHex.length() != 64) return false;
        byte[] expected = hmac(claimSecret(deviceId, enrollmentVersion).getBytes(StandardCharsets.UTF_8),
                deviceId + ":" + nonce);
        byte[] provided;
        try {
            provided = HEX.parseHex(proofHex.toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    public String newPairingCode() {
        StringBuilder code = new StringBuilder(8);
        for (int i = 0; i < 8; i++) code.append(PAIRING_ALPHABET[random.nextInt(PAIRING_ALPHABET.length)]);
        return code.toString();
    }

    public String newNonce() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    public String sha256Hex(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean hashMatches(String expectedHex, String value) {
        if (expectedHex == null || value == null) return false;
        return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.US_ASCII),
                sha256Hex(value).getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] masterKey() {
        String key = properties.getDeviceSecretKey();
        if (key == null || key.length() < 32) {
            throw new IllegalStateException(
                    "IOT_DEVICE_SECRET_KEY is not configured (needs at least 32 characters)");
        }
        return key.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
