package com.laundry.auth.exception;

/**
 * Exception thrown when JWT or refresh token is invalid, expired, or revoked.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
