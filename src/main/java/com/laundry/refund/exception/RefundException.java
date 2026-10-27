package com.laundry.refund.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

/**
 * Refund exception
 */
public class RefundException extends BusinessException {

    public RefundException(String message) {
        super(ErrorCode.REFUND_REJECTED, message);
    }

    public RefundException(String message, Throwable cause) {
        super(ErrorCode.REFUND_REJECTED, message);
        initCause(cause);
    }
}
