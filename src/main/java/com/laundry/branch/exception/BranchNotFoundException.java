package com.laundry.branch.exception;

import java.util.UUID;

/**
 * Thrown when a branch lookup by id yields no result.
 * Translated to HTTP 404 by {@code GlobalExceptionHandler}.
 */
public class BranchNotFoundException extends RuntimeException {

    public BranchNotFoundException(String message) {
        super(message);
    }

    public static BranchNotFoundException byId(UUID id) {
        return new BranchNotFoundException("Branch not found with ID: " + id);
    }
}
