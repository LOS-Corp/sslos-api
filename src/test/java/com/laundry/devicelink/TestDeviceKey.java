package com.laundry.devicelink;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** A board's P-256 key pair, made in Java the way the firmware makes it with mbedTLS. */
public final class TestDeviceKey {

    private final KeyPair pair;
    public final byte[] raw;

    public TestDeviceKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            pair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        ECPublicKey pub = (ECPublicKey) pair.getPublic();
        raw = new byte[65];
        raw[0] = 0x04;
        put(pub.getW().getAffineX(), raw, 1);
        put(pub.getW().getAffineY(), raw, 33);
    }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(raw);
    }

    /** DER signature of SHA-256(message), base64: what the board sends in {@code @proof}. */
    public String sign(String message) {
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(pair.getPrivate());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void put(BigInteger value, byte[] out, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, out, offset + 32 - length, length);
    }

    /** Opens a {@link DeviceKeys#seal sealed} secret the way the firmware does (ECDH with this key, AES-256-GCM). */
    public String open(String deviceId, String epk, String iv, String ct) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(pair.getPrivate());
            agreement.doPhase(DeviceKeys.toPublicKey(Base64.getDecoder().decode(epk)), true);
            byte[] key = DeviceKeys.credentialKey(agreement.generateSecret(), deviceId);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, Base64.getDecoder().decode(iv)));
            cipher.updateAAD(deviceId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(ct)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
