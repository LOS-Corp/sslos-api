package com.laundry.auth.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

/** Wrong email/phone or password (MSG02). The message never says which part was wrong. */
public class InvalidCredentialsException extends BusinessException {

    public InvalidCredentialsException() {
        super(ErrorCode.INVALID_CREDENTIALS, "Incorrect email/phone or password");
    }
}
