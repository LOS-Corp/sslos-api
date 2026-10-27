package com.laundry.shared.error;

import org.springframework.http.HttpStatus;

/**
 * Stable error codes returned in {@link ApiError#code()}. Clients branch on the code, never on the
 * English message. {@code messageCode} links the error to the SRS 5.3 message list (MSGxx) when one exists.
 */
public enum ErrorCode {

    // ── Generic ────────────────────────────────────────────────────────────────
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "MSG01"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, null),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, null),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, null),
    FORBIDDEN(HttpStatus.FORBIDDEN, "MSG22"),
    NOT_FOUND(HttpStatus.NOT_FOUND, null),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, null),
    CONFLICT(HttpStatus.CONFLICT, null),
    CONCURRENT_UPDATE(HttpStatus.CONFLICT, null),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, null),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, null),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, null),

    // ── Account & access (UC-01, UC-24) ───────────────────────────────────────
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "MSG02"),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "MSG03"),
    TOO_MANY_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "MSG03"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, null),
    BRANCH_OUT_OF_SCOPE(HttpStatus.FORBIDDEN, "MSG22"),

    // ── Booking (UC-02, BR-04…BR-09) ──────────────────────────────────────────
    HOLD_EXPIRED(HttpStatus.CONFLICT, "MSG06"),
    SLOT_TAKEN(HttpStatus.CONFLICT, "MSG07"),
    OUTSIDE_OPENING_HOURS(HttpStatus.CONFLICT, "MSG08"),
    BEYOND_ADVANCE_HORIZON(HttpStatus.BAD_REQUEST, "MSG09"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, null),
    MACHINE_TOO_SMALL(HttpStatus.CONFLICT, null),
    ADVANCE_QUOTA_REACHED(HttpStatus.CONFLICT, null),
    CONFIG_CONFLICT(HttpStatus.CONFLICT, "MSG23"),

    // ── Machine activation & devices (UC-03, UC-18) ───────────────────────────
    MACHINE_NOT_FOUND(HttpStatus.NOT_FOUND, null),
    BRANCH_NOT_FOUND(HttpStatus.NOT_FOUND, null),
    CHECK_IN_REJECTED(HttpStatus.BAD_REQUEST, "MSG12"),
    STALE_AUTHORIZATION(HttpStatus.CONFLICT, null),
    MACHINE_OFFLINE(HttpStatus.CONFLICT, "MSG13"),
    REASON_REQUIRED(HttpStatus.BAD_REQUEST, "MSG24"),
    RESOURCE_UNAVAILABLE(HttpStatus.CONFLICT, null),

    // ── Money (UC-08, UC-13, UC-25) ───────────────────────────────────────────
    PAYMENT_REJECTED(HttpStatus.CONFLICT, null),
    REFUND_REJECTED(HttpStatus.CONFLICT, null),
    REFUND_EXCEEDS_PAID(HttpStatus.CONFLICT, null),
    SELF_APPROVAL_FORBIDDEN(HttpStatus.CONFLICT, null),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, null),

    // ── Legacy transaction envelope ────────────────────────────────────────
    TRANSACTION_REJECTED(HttpStatus.BAD_REQUEST, null);

    private final HttpStatus status;
    private final String messageCode;

    ErrorCode(HttpStatus status, String messageCode) {
        this.status = status;
        this.messageCode = messageCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String messageCode() {
        return messageCode;
    }

    /** Generic code for a bare HTTP status (used for framework exceptions and legacy ResponseStatusException). */
    public static ErrorCode forStatus(int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 429 -> TOO_MANY_ATTEMPTS;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status >= 500 ? INTERNAL : INVALID_REQUEST;
        };
    }
}
