package com.laundry.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiError> business(BusinessException e, HttpServletRequest request) {
        return build(e.code(), e.getMessage(), Map.of(), e.details(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors().forEach(g -> fields.putIfAbsent(g.getObjectName(), g.getDefaultMessage()));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.message(), fields, Map.of(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(ConstraintViolationException e, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(v -> fields.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.message(), fields, Map.of(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> unreadable(Exception e, HttpServletRequest request) {
        return build(ErrorCode.BAD_REQUEST, "Yêu cầu không đúng định dạng", Map.of(), Map.of(), request);
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ApiError> denied(Exception e, HttpServletRequest request) {
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.message(), Map.of(), Map.of(), request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> optimistic(Exception e, HttpServletRequest request) {
        return build(ErrorCode.CONFLICT, ErrorCode.CONFLICT.message(), Map.of(), Map.of(), request);
    }

    /** Ràng buộc DB là lớp chặn cuối (chồng lịch, hoàn vượt số đã trả, trùng khóa...). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException e, HttpServletRequest request) {
        String text = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        ErrorCode code = text.contains("no_overlap") ? ErrorCode.SCHEDULE_CONFLICT
                : text.contains("refund exceeds") ? ErrorCode.REFUND_EXCEEDS_PAID
                : ErrorCode.CONFLICT;
        log.warn("Data integrity violation on {}: {}", request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return build(code, code.message(), Map.of(), Map.of(), request);
    }

    @ExceptionHandler({NoResourceFoundException.class})
    ResponseEntity<ApiError> noResource(Exception e, HttpServletRequest request) {
        return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.message(), Map.of(), Map.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(Exception e, HttpServletRequest request) {
        return build(ErrorCode.BAD_REQUEST, "Phương thức không được hỗ trợ", Map.of(), Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return build(ErrorCode.INTERNAL, ErrorCode.INTERNAL.message(), Map.of(), Map.of(), request);
    }

    private ResponseEntity<ApiError> build(ErrorCode code, String message, Map<String, String> fields,
                                           Map<String, Object> details, HttpServletRequest request) {
        ApiError body = new ApiError(Instant.now(), code.status().value(), code.name(), message, fields, details,
                request.getRequestURI());
        return ResponseEntity.status(code.status()).body(body);
    }
}
