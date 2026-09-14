package com.laundry.transaction.exception;

/**
 * Exception thrown when double booking is detected
 */
public class DoubleBookingException extends RuntimeException {

    public DoubleBookingException(String message) {
        super(message);
    }

    public DoubleBookingException(String resourceType, String resourceId) {
        super(String.format("Resource %s %s is already booked", resourceType, resourceId));
    }
}
