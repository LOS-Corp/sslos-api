package com.laundry.branch.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

import java.util.UUID;

/**
 * Thrown when a branch lookup by id yields no result.
 * Translated to HTTP 404 by {@code GlobalExceptionHandler}.
 */
public class BranchNotFoundException extends BusinessException {

    public BranchNotFoundException(String message) {
        super(ErrorCode.BRANCH_NOT_FOUND, message);
    }

    public static BranchNotFoundException byId(UUID id) {
        return new BranchNotFoundException("Branch not found with ID: " + id);
    }
}
