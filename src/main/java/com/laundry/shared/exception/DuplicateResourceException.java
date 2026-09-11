package com.laundry.shared.exception;

/**
 * Exception thrown when a duplicate resource is detected (e.g., duplicate email, phone, etc.).
 * Can be used across different modules for various resource types.
 */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
