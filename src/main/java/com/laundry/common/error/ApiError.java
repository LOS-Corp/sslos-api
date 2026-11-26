package com.laundry.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/** Định dạng lỗi chung của mọi API. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        Map<String, String> fields,
        Map<String, Object> details,
        String path
) {
    public static ApiError of(ErrorCode code, String message, String path) {
        return new ApiError(Instant.now(), code.status().value(), code.name(), message, Map.of(), Map.of(), path);
    }
}
