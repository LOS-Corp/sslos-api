package com.laundry.machine.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

import java.util.Map;
import java.util.UUID;

/** A START_REQUEST referenced an authorization that is no longer valid; the caller may re-scan the QR. */
public class StaleAuthorizationException extends BusinessException {

    private final UUID authorizationId;

    public StaleAuthorizationException(UUID authorizationId, String message) {
        super(ErrorCode.STALE_AUTHORIZATION, message, Map.of("authorizationId", String.valueOf(authorizationId)));
        this.authorizationId = authorizationId;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }
}
