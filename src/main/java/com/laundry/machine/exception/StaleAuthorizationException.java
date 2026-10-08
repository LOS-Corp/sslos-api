package com.laundry.machine.exception;

import java.util.UUID;

/**
 * Thrown when a START_REQUEST arrives with an authorizationId we no
 * longer recognize (revoked, expired, or simply unknown). Maps to HTTP 409.
 */
public class StaleAuthorizationException extends RuntimeException {
    private final UUID authorizationId;

    public StaleAuthorizationException(UUID authorizationId, String message) {
        super(message);
        this.authorizationId = authorizationId;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }
}
