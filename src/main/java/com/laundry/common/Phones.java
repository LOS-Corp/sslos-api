package com.laundry.common;

import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;

/** Số điện thoại lưu dạng E.164: "0901234567" -> "+84901234567". */
public final class Phones {

    private Phones() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[\\s.\\-()]", "");
        if (digits.startsWith("00")) digits = "+" + digits.substring(2);
        if (digits.startsWith("0")) digits = "+84" + digits.substring(1);
        if (!digits.startsWith("+")) digits = "+" + digits;
        if (!digits.matches("^\\+[1-9][0-9]{7,14}$")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Số điện thoại không hợp lệ");
        }
        return digits;
    }

    /** 4 số cuối, dùng khi check-in bằng mã đơn (mất điện thoại). */
    public static String lastFour(String e164) {
        return e164 == null || e164.length() < 4 ? "" : e164.substring(e164.length() - 4);
    }

    /** "+84901234567" -> "+84*****4567" cho màn hình kiosk / nhật ký. */
    public static String mask(String e164) {
        if (e164 == null || e164.length() < 7) return e164;
        return e164.substring(0, 3) + "*".repeat(e164.length() - 7) + e164.substring(e164.length() - 4);
    }
}
