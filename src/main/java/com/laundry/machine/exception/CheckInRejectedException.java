package com.laundry.machine.exception;

/**
 * Thrown when a customer's check-in attempt is rejected by business rules
 * (machine offline / wrong branch / reservation not eligible / etc.).
 * Maps to HTTP 400 by the global exception handler.
 */
public class CheckInRejectedException extends RuntimeException {
    public CheckInRejectedException(String message) {
        super(message);
    }

    /**
     * Returns a stable string code that the frontend can branch on without
     * parsing free-form messages.
     */
    public String code() {
        return "CHECK_IN_REJECTED";
    }
}
