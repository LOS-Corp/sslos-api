package com.laundry.shared.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

/**
 * Thrown when a request contains invalid data that cannot be processed,
 * distinct from a missing resource (404) or conflict (409).
 */
public class InvalidRequestException extends BusinessException {

    public InvalidRequestException(String message) {
        super(ErrorCode.INVALID_REQUEST, message);
    }
}
