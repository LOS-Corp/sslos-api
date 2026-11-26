package com.laundry.common.error;

import org.springframework.http.HttpStatus;

/** Mã lỗi nghiệp vụ trả cho client: {@code code} ổn định để frontend xử lý, {@code message} hiển thị cho người dùng. */
public enum ErrorCode {

    // Chung
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "Yêu cầu không hợp lệ"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy"),
    CONFLICT(HttpStatus.CONFLICT, "Dữ liệu bị xung đột, vui lòng thử lại"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập hoặc phiên đã hết hạn"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này"),
    REASON_REQUIRED(HttpStatus.BAD_REQUEST, "Thao tác này bắt buộc ghi lý do"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Quá nhiều yêu cầu, vui lòng thử lại sau"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống"),

    // Tài khoản
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng"),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "Mã xác thực không hợp lệ hoặc đã hết hạn"),
    EMAIL_TAKEN(HttpStatus.CONFLICT, "Email đã được sử dụng"),
    PHONE_TAKEN(HttpStatus.CONFLICT, "Số điện thoại đã được sử dụng"),
    WRONG_PASSWORD(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không đúng"),
    BOOKING_BANNED(HttpStatus.FORBIDDEN, "Tài khoản đang bị tạm khóa quyền đặt lịch do vi phạm"),

    // Cửa hàng / máy
    STORE_NOT_READY(HttpStatus.CONFLICT, "Cửa hàng chưa sẵn sàng nhận đặt"),
    STORE_CLOSED(HttpStatus.CONFLICT, "Ngoài giờ mở cửa"),
    SERVICE_DISABLED(HttpStatus.CONFLICT, "Dịch vụ đang tạm ngừng"),
    SERVICE_IN_USE(HttpStatus.CONFLICT, "Đang có khách dùng dịch vụ này, chưa tắt được"),
    CONFIG_BREAKS_ORDERS(HttpStatus.CONFLICT, "Thay đổi làm một số đơn đã xác nhận không chạy được"),
    MACHINE_NOT_SELLABLE(HttpStatus.CONFLICT, "Máy chưa có giá hoặc đang không bán"),
    MACHINE_UNAVAILABLE(HttpStatus.CONFLICT, "Máy không còn trống trong khoảng thời gian này"),
    SCHEDULE_CONFLICT(HttpStatus.CONFLICT, "Trùng với lượt đã đặt hoặc lịch bảo trì"),
    QUOTA_EXCEEDED(HttpStatus.CONFLICT, "Khung giờ này đã hết chỗ đặt trước"),
    MACHINE_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "Loại máy không phù hợp với dịch vụ"),

    // Thiết bị
    DEVICE_NOT_CONNECTED(HttpStatus.SERVICE_UNAVAILABLE, "Máy đang mất kết nối"),
    DEVICE_REFUSED(HttpStatus.FORBIDDEN, "Thiết bị bị từ chối"),
    DEVICE_STATE(HttpStatus.CONFLICT, "Thiết bị không ở trạng thái phù hợp"),

    // Đơn
    ORDER_STATE(HttpStatus.CONFLICT, "Đơn không ở trạng thái phù hợp"),
    TOO_EARLY(HttpStatus.CONFLICT, "Chưa đến giờ"),
    CHECKIN_CLOSED(HttpStatus.CONFLICT, "Đã hết thời gian check-in"),
    RESCHEDULE_NOT_ALLOWED(HttpStatus.CONFLICT, "Không thể đổi giờ đơn này"),
    ADVANCE_LIMIT(HttpStatus.BAD_REQUEST, "Giờ đặt vượt quá giới hạn đặt trước"),

    // Tiền
    PAYMENT_EXPIRED(HttpStatus.CONFLICT, "Mã thanh toán đã hết hạn"),
    PAYMENT_GATEWAY(HttpStatus.BAD_GATEWAY, "Không tạo được mã thanh toán, vui lòng thử lại"),
    INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "Số dư ví không đủ"),
    REFUND_EXCEEDS_PAID(HttpStatus.CONFLICT, "Số tiền hoàn vượt số khách đã trả"),
    APPROVAL_REQUIRED(HttpStatus.FORBIDDEN, "Khoản này cần chủ cửa hàng duyệt");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
