package com.laundry.machine.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

import java.util.Map;

/**
 * QR check-in refused by a business rule (wrong machine, unpaid, window closed…).
 * {@code fields.code} is kept for clients written before {@code ApiError.code} existed.
 */
public class CheckInRejectedException extends BusinessException {

    public CheckInRejectedException(String message) {
        super(ErrorCode.CHECK_IN_REJECTED, message, Map.of("code", ErrorCode.CHECK_IN_REJECTED.name()));
    }
}
