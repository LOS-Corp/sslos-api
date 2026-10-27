package com.laundry.payment.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

/**
 * Exception for payment errors
 */
public class PaymentException extends BusinessException {

    public PaymentException(String message) {
        super(ErrorCode.PAYMENT_REJECTED, message);
    }

    public PaymentException(String message, Throwable cause) {
        super(ErrorCode.PAYMENT_REJECTED, message);
        initCause(cause);
    }
}
