package com.laundry.shared.exception;

/**
 * Thrown when a request contains invalid data that cannot be processed,
 * distinct from a missing resource (404) or conflict (409).
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
