package com.laundry.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Mã ngẫu nhiên và băm. QR chỉ chứa mã ngẫu nhiên; DB lưu SHA-256 hex của mã đó. */
public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** Mã đơn 6 ký tự: không có 0/O/1/I để đọc qua điện thoại không nhầm (khớp CHECK của orders.code). */
    private static final char[] ORDER_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private Tokens() {
    }

    /** Mã ngẫu nhiên base64url, {@code bytes} byte entropy. */
    public static String random(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String orderCode() {
        char[] out = new char[6];
        for (int i = 0; i < out.length; i++) out[i] = ORDER_CODE_ALPHABET[RANDOM.nextInt(ORDER_CODE_ALPHABET.length)];
        return new String(out);
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** So sánh hằng thời gian. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
