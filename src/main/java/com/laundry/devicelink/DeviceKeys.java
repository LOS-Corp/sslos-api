package com.laundry.devicelink;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Board identity keys for the Wi-Fi link: ECDSA P-256 (secp256r1), the curve the ESP32's mbedTLS
 * signs with in hardware-friendly time.
 *
 * <ul>
 *   <li>public key = the 65-byte uncompressed point {@code 04 || X || Y}, base64 in HELLO;</li>
 *   <li>the board signs SHA-256 of {@link #challengeMessage} and sends the DER signature, base64;</li>
 *   <li>fingerprint = SHA-256 of the public key, 64 hex digits; screens show the first 8 as {@code A1B2-C3D4}.</li>
 *   <li>{@link #seal}: a secret only the board can read (its own MQTT password), ECIES on the same key:
 *       ephemeral ECDH P-256, key = SHA-256(shared X || "LAUNDRYOS-CRED|" || deviceId), AES-256-GCM, AAD = deviceId.</li>
 * </ul>
 * Pure functions only, so the hub and the tests share one implementation.
 */
public final class DeviceKeys {

    private static final int POINT_LENGTH = 65, NONCE_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ECParameterSpec P256 = p256();

    private DeviceKeys() {}

    /** The exact text the board signs: binds the signature to this board and this nonce. */
    public static String challengeMessage(String deviceId, String nonce) {
        return "LAUNDRYOS-AUTH|" + deviceId + "|" + nonce;
    }

    /** What the board signs to register over HTTP; a different label, so an MQTT proof cannot be replayed here. */
    public static String registerMessage(String deviceId, String nonce) {
        return "LAUNDRYOS-REGISTER|" + deviceId + "|" + nonce;
    }

    /** A fresh single-use nonce (base64, no padding). */
    public static String newNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Decodes and checks a base64 public key; empty when it is not a P-256 point. */
    public static Optional<byte[]> decodePublicKey(String base64) {
        if (base64 == null || base64.isBlank() || base64.length() > 200) return Optional.empty();
        try {
            byte[] raw = Base64.getDecoder().decode(base64.trim());
            toPublicKey(raw);
            return Optional.of(raw);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return Optional.empty();
        }
    }

    /** True when {@code signatureBase64} is a valid signature of {@code message} by {@code publicKey}. */
    public static boolean verify(byte[] publicKey, String message, String signatureBase64) {
        if (signatureBase64 == null || signatureBase64.length() > 200) return false;
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(toPublicKey(publicKey));
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            return false;
        }
    }

    /** SHA-256 of the public key, upper-case hex (64 digits). */
    public static String fingerprint(byte[] publicKey) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** The 8 digits printed on the board's screen, e.g. {@code A1B2-C3D4}. */
    public static String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.length() < 8) return null;
        return fingerprint.substring(0, 4) + "-" + fingerprint.substring(4, 8);
    }

    public static String encode(byte[] publicKey) {
        return Base64.getEncoder().encodeToString(publicKey);
    }

    public static byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    /** A secret sealed for one board: base64 of the ephemeral public point, the GCM IV and ciphertext||tag. */
    public record Sealed(String epk, String iv, String ct) {
    }

    /** Encrypts {@code secret} so that only the holder of the private half of {@code boardPublicKey} can read it. */
    public static Sealed seal(byte[] boardPublicKey, String deviceId, String secret) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(P256, RANDOM);
            KeyPair ephemeral = generator.generateKeyPair();
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
            agreement.init(ephemeral.getPrivate());
            agreement.doPhase(toPublicKey(boardPublicKey), true);
            byte[] key = credentialKey(agreement.generateSecret(), deviceId);
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(deviceId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder b64 = Base64.getEncoder();
            return new Sealed(b64.encodeToString(rawPoint((ECPublicKey) ephemeral.getPublic())),
                    b64.encodeToString(iv), b64.encodeToString(ct));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot seal a secret for " + deviceId, e);
        }
    }

    /** SHA-256(shared X || "LAUNDRYOS-CRED|" || deviceId): the AES-256 key of {@link #seal}. */
    static byte[] credentialKey(byte[] sharedSecret, String deviceId) throws GeneralSecurityException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(sharedSecret);
        sha.update(("LAUNDRYOS-CRED|" + deviceId).getBytes(StandardCharsets.UTF_8));
        return sha.digest();
    }

    /** {@code 04 || X || Y}, 32 bytes each. */
    static byte[] rawPoint(ECPublicKey key) {
        byte[] out = new byte[POINT_LENGTH];
        out[0] = 0x04;
        put(key.getW().getAffineX(), out, 1);
        put(key.getW().getAffineY(), out, 33);
        return out;
    }

    private static void put(BigInteger value, byte[] out, int offset) {
        byte[] bytes = value.toByteArray();
        int start = Math.max(0, bytes.length - 32);
        int length = bytes.length - start;
        System.arraycopy(bytes, start, out, offset + 32 - length, length);
    }

    static PublicKey toPublicKey(byte[] raw) throws GeneralSecurityException {
        if (raw == null || raw.length != POINT_LENGTH || raw[0] != 0x04) {
            throw new GeneralSecurityException("Not an uncompressed P-256 point");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        if (!onCurve(x, y)) throw new GeneralSecurityException("Point is not on P-256");
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    /** y² = x³ + ax + b (mod p): rejects invalid-curve points before they reach the verifier. */
    private static boolean onCurve(BigInteger x, BigInteger y) {
        BigInteger p = ((java.security.spec.ECFieldFp) P256.getCurve().getField()).getP();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) return false;
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.pow(3).add(P256.getCurve().getA().multiply(x)).add(P256.getCurve().getB()).mod(p);
        return left.equals(right);
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 unavailable", e);
        }
    }
}
