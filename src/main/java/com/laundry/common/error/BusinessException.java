package com.laundry.common.error;

import java.util.Map;

/** Lỗi nghiệp vụ có chủ đích; GlobalExceptionHandler đổi thành ApiError với status của {@link ErrorCode}. */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public BusinessException(ErrorCode code) {
        this(code, code.message(), Map.of());
    }

    public BusinessException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public BusinessException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static BusinessException notFound(String what) {
        return new BusinessException(ErrorCode.NOT_FOUND, "Không tìm thấy " + what);
    }
}
