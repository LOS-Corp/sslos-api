package com.laundry.shared.error;

import org.slf4j.MDC;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** Builds {@link ApiError} bodies so MVC handlers and security filters answer with the same shape. */
public final class ApiErrors {

    public static final String TRACE_ID = "traceId";

    private ApiErrors() {
    }

    public static ApiError of(ErrorCode code, String message) {
        return of(code, message, Map.of());
    }

    public static ApiError of(ErrorCode code, String message, Map<String, String> fields) {
        return of(code, code.status().value(), message, fields);
    }

    public static ApiError of(ErrorCode code, int status, String message, Map<String, String> fields) {
        return new ApiError(OffsetDateTime.now(ZoneOffset.UTC), status, message, fields,
                code.name(), code.messageCode(), MDC.get(TRACE_ID));
    }
}
