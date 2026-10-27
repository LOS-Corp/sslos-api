package com.laundry.auth.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

/**
 * Exception thrown when JWT or refresh token is invalid, expired, or revoked.
 */
public class InvalidTokenException extends BusinessException {

    public InvalidTokenException(String message) {
        super(ErrorCode.INVALID_TOKEN, message);
    }
}
