package com.laundry.transaction.exception;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;

/**
 * Exception thrown when a resource is not available
 */
public class ResourceNotAvailableException extends BusinessException {

    public ResourceNotAvailableException(String message) {
        super(ErrorCode.RESOURCE_UNAVAILABLE, message);
    }

    public ResourceNotAvailableException(String resourceType, String resourceId) {
        super(ErrorCode.RESOURCE_UNAVAILABLE, String.format("%s %s is not available", resourceType, resourceId));
    }
}
