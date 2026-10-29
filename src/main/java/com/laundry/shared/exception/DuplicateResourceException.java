package com.laundry.shared.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

/**
 * Exception thrown when a duplicate resource is detected (e.g., duplicate email, phone, etc.).
 * Can be used across different modules for various resource types.
 */
public class DuplicateResourceException extends BusinessException {

    public DuplicateResourceException(String message) {
        super(ErrorCode.DUPLICATE_RESOURCE, message);
    }
}
