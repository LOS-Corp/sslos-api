package com.laundry.payment.entity;

/**
 * Payment status
 */
public enum PaymentStatus {
    PENDING,    // Chờ thanh toán
    PAID,       // Đã thanh toán thành công
    FAILED,     // Thanh toán thất bại
    CANCELLED,  // Đã hủy
    REVERSED    // Phần trả bằng ví đã được hoàn lại (payOS thất bại hoặc hết hạn giữ chỗ)
}
