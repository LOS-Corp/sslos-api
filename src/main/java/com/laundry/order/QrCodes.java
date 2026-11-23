package com.laundry.order;

import com.laundry.common.Tokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Mã QR của đơn / biên nhận / vé locker: HMAC(bí mật, loại|id), không chứa thông tin cá nhân (§7.4).
 * App tính lại được bất cứ lúc nào để hiện QR; DB chỉ lưu SHA-256 của mã để kiosk tra.
 */
@Component
public class QrCodes {

    private final String secret;

    public QrCodes(@Value("${app.qr.secret:${app.jwt.secret}}") String secret) {
        this.secret = secret;
    }

    public String orderToken(UUID orderId) {
        return "O" + Tokens.hmacSha256Hex(secret, "order|" + orderId).substring(0, 31);
    }

    public String lockerTicketToken(UUID assignmentId) {
        return "L" + Tokens.hmacSha256Hex(secret, "locker|" + assignmentId).substring(0, 31);
    }

    public static String hash(String token) {
        return Tokens.sha256Hex(token.trim());
    }
}
