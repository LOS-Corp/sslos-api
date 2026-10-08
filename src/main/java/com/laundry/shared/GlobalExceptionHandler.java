package com.laundry.shared;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import com.laundry.auth.exception.InvalidCredentialsException;
import com.laundry.auth.exception.InvalidTokenException;
import com.laundry.machine.exception.CheckInRejectedException;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.machine.exception.StaleAuthorizationException;
import com.laundry.shared.exception.DuplicateResourceException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
            .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", fields);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> handleIllegalState(IllegalStateException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    ResponseEntity<ApiError> handleDuplicate(DuplicateResourceException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MachineNotFoundException.class)
    ResponseEntity<ApiError> handleMachineNotFound(MachineNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    /**
     * QR check-in rejected by business rules. Maps to 400 with a stable
     * {@code code} field so the mobile app can branch on it.
     */
    @ExceptionHandler(CheckInRejectedException.class)
    ResponseEntity<ApiError> handleCheckInRejected(CheckInRejectedException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(OffsetDateTime.now(),
                        HttpStatus.BAD_REQUEST.value(),
                        exception.getMessage(),
                        Map.of("code", exception.code())));
    }

    /**
     * Authorization referenced by a START_REQUEST is no longer valid.
     * Maps to 409 — caller may want to re-scan the QR.
     */
    @ExceptionHandler(StaleAuthorizationException.class)
    ResponseEntity<ApiError> handleStaleAuth(StaleAuthorizationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(OffsetDateTime.now(),
                        HttpStatus.CONFLICT.value(),
                        exception.getMessage(),
                        Map.of("authorizationId", String.valueOf(exception.getAuthorizationId()))));
    }

    @ExceptionHandler(com.laundry.branch.exception.BranchNotFoundException.class)
    ResponseEntity<ApiError> handleBranchNotFound(com.laundry.branch.exception.BranchNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<ApiError> handleInvalidCredentials() {
        return error(HttpStatus.UNAUTHORIZED, "Invalid email or password", Map.of());
    }

    @ExceptionHandler(InvalidTokenException.class)
    ResponseEntity<ApiError> handleInvalidToken(InvalidTokenException exception) {
        return error(HttpStatus.UNAUTHORIZED, exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception) {
        return error(HttpStatus.FORBIDDEN, "Access denied: " + exception.getMessage(), Map.of());
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message, Map<String, String> fields) {
        return ResponseEntity.status(status)
            .body(new ApiError(OffsetDateTime.now(), status.value(), message, fields));
    }
}
