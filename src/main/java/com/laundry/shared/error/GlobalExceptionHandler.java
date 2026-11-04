package com.laundry.shared.error;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only exception handler of the API. Every error leaves as {@link ApiError} (SRS 5.2)
 * with a stable {@link ErrorCode}; unexpected failures never leak internals.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Each handler reads: WHEN it fires -> WHAT the client gets (status CODE, message).
    // Spring picks the handler whose type is closest to the thrown exception;
    // unexpected(Exception) at the bottom catches whatever nothing else matched.

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String UNIQUE_VIOLATION = "23505";

    // A service broke a business rule: throw BusinessException.of(ErrorCode.X, "...") or a subclass
    // (InvalidCredentialsException, DuplicateResourceException...) -> ex.code()'s status, ex's message.
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException ex) {
        return respond(ex.code(), ex.getMessage(), ex.details());
    }

    // @Valid @RequestBody failed (@NotBlank, @Email...) -> 400 VALIDATION_FAILED, fields = {field: reason}.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields);
    }

    // Constraint on a @PathVariable / @RequestParam failed (@Min, @Size...) -> 400 VALIDATION_FAILED, fields.
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParameters(HandlerMethodValidationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                fields.putIfAbsent(result.getMethodParameter().getParameterName(), error.getDefaultMessage())));
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields);
    }

    // Bean validation outside the controller (@Validated service) failed -> 400 VALIDATION_FAILED, fields.
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraintViolation(ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields);
    }

    // Broken JSON, wrong type (?id=abc for a Long) or missing required param -> 400 MALFORMED_REQUEST.
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> malformed(Exception ex) {
        return respond(ErrorCode.MALFORMED_REQUEST, "Malformed request", Map.of());
    }

    // Logged in but role not allowed (@PreAuthorize denied) -> 403 FORBIDDEN.
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ApiError> forbidden(Exception ex) {
        return respond(ErrorCode.FORBIDDEN, "You do not have permission for this action", Map.of());
    }

    // Not logged in, raised inside a controller/service. Missing/bad JWT at the filter never gets
    // here: SecurityErrorResponder answers that one with the same body -> 401 UNAUTHENTICATED.
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> unauthenticated(AuthenticationException ex) {
        return respond(ErrorCode.UNAUTHENTICATED, "Authentication required", Map.of());
    }

    // Two requests saved the same @Version row at once; the later one loses -> 409 CONCURRENT_UPDATE.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> concurrentUpdate(ObjectOptimisticLockingFailureException ex) {
        return respond(ErrorCode.CONCURRENT_UPDATE, "The record was changed by another request. Please retry.", Map.of());
    }

    // The database rejected the write. Told apart by SQLState:
    //   23P01 exclusion constraint (overlapping booking) -> 409 SLOT_TAKEN
    //   23505 unique constraint (duplicate email...)      -> 409 DUPLICATE_RESOURCE
    //   anything else (FK, NOT NULL...)                   -> 409 CONFLICT, logged
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException ex) {
        String sqlState = sqlState(ex);
        if (EXCLUSION_VIOLATION.equals(sqlState)) {
            return respond(ErrorCode.SLOT_TAKEN, "This time is no longer available", Map.of());
        }
        if (UNIQUE_VIOLATION.equals(sqlState)) {
            return respond(ErrorCode.DUPLICATE_RESOURCE, "The resource already exists", Map.of());
        }
        log.error("Data integrity violation (sqlState={})", sqlState, ex);
        return respond(ErrorCode.CONFLICT, "The request conflicts with existing data", Map.of());
    }

    // Legacy throw new ResponseStatusException(status, reason) -> that status, code from
    // ErrorCode.forStatus. New code must not use it (ArchUnit).
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> responseStatus(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String reason = ex.getReason() != null ? ex.getReason() : "Request failed";
        return ResponseEntity.status(status)
                .body(ApiErrors.of(ErrorCode.forStatus(status), status, reason, Map.of()));
    }

    // URL matches no endpoint or static file -> 404 NOT_FOUND.
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException ex) {
        return respond(ErrorCode.NOT_FOUND, "Resource not found", Map.of());
    }

    // Multipart upload above the configured limit -> 413 FILE_TOO_LARGE.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException ex) {
        return respond(ErrorCode.FILE_TOO_LARGE, "The file is larger than 5 MB", Map.of());
    }

    // Right URL, wrong verb (GET on a POST-only endpoint) -> 405 METHOD_NOT_ALLOWED.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, "Method not allowed", Map.of());
    }

    /**
     * Legacy services still signal rule violations with IllegalArgument/IllegalState. Their messages are
     * written for callers; new code throws {@link BusinessException} instead (ArchUnit-guarded).
     */
    // Legacy: bad input from the caller -> 400 INVALID_REQUEST, ex's message.
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> illegalArgument(IllegalArgumentException ex) {
        return respond(ErrorCode.INVALID_REQUEST, ex.getMessage(), Map.of());
    }

    // Legacy: action not allowed in the current state -> 409 CONFLICT, ex's message.
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> illegalState(IllegalStateException ex) {
        return respond(ErrorCode.CONFLICT, ex.getMessage(), Map.of());
    }

    // Safety net for everything else (NPE, DB down, bugs) -> 500 INTERNAL.
    // Full stacktrace goes to the log only; the client gets the traceId to quote, never ex's message.
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL,
                "Unexpected error. Reference: " + MDC.get(ApiErrors.TRACE_ID), Map.of());
    }

    // Every handler above ends here: status from the ErrorCode, body built by ApiErrors (adds timestamp, traceId).
    private static ResponseEntity<ApiError> respond(ErrorCode code, String message, Map<String, String> fields) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code, message, fields));
    }

    // Walks the cause chain to the JDBC SQLException; Spring wraps it several layers deep.
    private static String sqlState(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
