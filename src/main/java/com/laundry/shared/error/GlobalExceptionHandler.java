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

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String UNIQUE_VIOLATION = "23505";

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException ex) {
        return respond(ex.code(), ex.getMessage(), ex.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> invalidParameters(HandlerMethodValidationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getParameterValidationResults().forEach(result -> result.getResolvableErrors().forEach(error ->
                fields.putIfAbsent(result.getMethodParameter().getParameterName(), error.getDefaultMessage())));
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraintViolation(ConstraintViolationException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getConstraintViolations()
                .forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return respond(ErrorCode.VALIDATION_FAILED, "Request validation failed", fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> malformed(Exception ex) {
        return respond(ErrorCode.MALFORMED_REQUEST, "Malformed request", Map.of());
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ApiError> forbidden(Exception ex) {
        return respond(ErrorCode.FORBIDDEN, "You do not have permission for this action", Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> unauthenticated(AuthenticationException ex) {
        return respond(ErrorCode.UNAUTHENTICATED, "Authentication required", Map.of());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> concurrentUpdate(ObjectOptimisticLockingFailureException ex) {
        return respond(ErrorCode.CONCURRENT_UPDATE, "The record was changed by another request. Please retry.", Map.of());
    }

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

    /** Legacy {@code throw new ResponseStatusException(...)} sites keep their status and reason. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> responseStatus(ResponseStatusException ex) {
        int status = ex.getStatusCode().value();
        String reason = ex.getReason() != null ? ex.getReason() : "Request failed";
        return ResponseEntity.status(status)
                .body(ApiErrors.of(ErrorCode.forStatus(status), status, reason, Map.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException ex) {
        return respond(ErrorCode.NOT_FOUND, "Resource not found", Map.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException ex) {
        return respond(ErrorCode.FILE_TOO_LARGE, "The file is larger than 5 MB", Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, "Method not allowed", Map.of());
    }

    /**
     * Legacy services still signal rule violations with IllegalArgument/IllegalState. Their messages are
     * written for callers; new code throws {@link BusinessException} instead (ArchUnit-guarded).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> illegalArgument(IllegalArgumentException ex) {
        return respond(ErrorCode.INVALID_REQUEST, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> illegalState(IllegalStateException ex) {
        return respond(ErrorCode.CONFLICT, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL,
                "Unexpected error. Reference: " + MDC.get(ApiErrors.TRACE_ID), Map.of());
    }

    private static ResponseEntity<ApiError> respond(ErrorCode code, String message, Map<String, String> fields) {
        return ResponseEntity.status(code.status()).body(ApiErrors.of(code, message, fields));
    }

    private static String sqlState(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
