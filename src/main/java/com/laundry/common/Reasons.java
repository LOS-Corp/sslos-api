package com.laundry.common;

import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;

/** Thao tác nhạy cảm bắt buộc ghi lý do (§2). */
public final class Reasons {

    private Reasons() {
    }

    public static String require(String reason) {
        if (reason == null || reason.strip().length() < 3) throw new BusinessException(ErrorCode.REASON_REQUIRED);
        String r = reason.strip();
        return r.length() > 500 ? r.substring(0, 500) : r;
    }
}
