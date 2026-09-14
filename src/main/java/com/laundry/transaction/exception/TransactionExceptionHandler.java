package com.laundry.transaction.exception;

import com.laundry.shared.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Exception handler for transaction module
 */
@RestControllerAdvice
public class TransactionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TransactionExceptionHandler.class);

    @ExceptionHandler(ResourceNotAvailableException.class)
    public ResponseEntity<ApiError> handleResourceNotAvailable(ResourceNotAvailableException ex) {
        log.warn("Resource not available: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError(OffsetDateTime.now(), HttpStatus.CONFLICT.value(), ex.getMessage(), null));
    }

    @ExceptionHandler(DoubleBookingException.class)
    public ResponseEntity<ApiError> handleDoubleBooking(DoubleBookingException ex) {
        log.warn("Double booking detected: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError(OffsetDateTime.now(), HttpStatus.CONFLICT.value(), ex.getMessage(), null));
    }

    @ExceptionHandler(TransactionException.class)
    public ResponseEntity<ApiError> handleTransactionException(TransactionException ex) {
        log.warn("Transaction error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ApiError(OffsetDateTime.now(), HttpStatus.BAD_REQUEST.value(), ex.getMessage(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        log.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(new ApiError(OffsetDateTime.now(), HttpStatus.BAD_REQUEST.value(), "Validation failed", errors));
    }
}
