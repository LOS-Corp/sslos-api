package com.laundry.transaction.exception;

/**
 * Exception thrown when a resource is not available
 */
public class ResourceNotAvailableException extends RuntimeException {

    public ResourceNotAvailableException(String message) {
        super(message);
    }

    public ResourceNotAvailableException(String resourceType, String resourceId) {
        super(String.format("%s %s is not available", resourceType, resourceId));
    }
}
