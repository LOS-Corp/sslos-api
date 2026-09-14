package com.laundry.payment.entity;

/**
 * Payment status
 */
public enum PaymentStatus {
    PENDING,    // Chờ thanh toán
    PAID,       // Đã thanh toán thành công
    FAILED,     // Thanh toán thất bại
    CANCELLED   // Đã hủy
}
