package com.laundry.shared.error;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Error body of every API (SRS 5.2: {timestamp, status, message, fields}).
 * {@code code}, {@code messageCode} and {@code traceId} are additive, so older clients keep working.
 */
public record ApiError(OffsetDateTime timestamp,
                       int status,
                       String message,
                       Map<String, String> fields,
                       String code,
                       String messageCode,
                       String traceId) {
}
