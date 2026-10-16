package com.laundry.iot.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A provisioning step was refused. Services mark it {@code noRollbackFor} where
 * the refusal itself must persist (failed-attempt counter, consumed nonce).
 */
public class ProvisioningRejectedException extends ResponseStatusException {

    public ProvisioningRejectedException(HttpStatus status, String reason) {
        super(status, reason);
    }
}
