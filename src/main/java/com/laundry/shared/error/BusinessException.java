package com.laundry.shared.error;

import java.util.Map;

/**
 * A request that broke a business rule. The message is safe to show to the caller;
 * {@code details} carries machine-readable context (e.g. {@code authorizationId}).
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, String> details;

    public BusinessException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public BusinessException(ErrorCode code, String message, Map<String, String> details) {
        super(message);
        this.code = code;
        this.details = Map.copyOf(details);
    }

    public static BusinessException of(ErrorCode code, String message) {
        return new BusinessException(code, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN, message);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, String> details() {
        return details;
    }
}
