package com.laundry.transaction.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

/**
 * Exception thrown when double booking is detected
 */
public class DoubleBookingException extends BusinessException {

    public DoubleBookingException(String message) {
        super(ErrorCode.SLOT_TAKEN, message);
    }

    public DoubleBookingException(String resourceType, String resourceId) {
        super(ErrorCode.SLOT_TAKEN, String.format("Resource %s %s is already booked", resourceType, resourceId));
    }
}
