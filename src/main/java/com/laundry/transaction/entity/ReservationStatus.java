package com.laundry.transaction.entity;

/**
 * Machine reservation status
 */
public enum ReservationStatus {
    RESERVED,    // Đã đặt (chờ thanh toán)
    CONFIRMED,   // Đã xác nhận
    ACTIVE,      // Đang hoạt động
    COMPLETED,   // Hoàn thành
    CANCELLED,   // Đã hủy
    EXPIRED      // Hết hạn
}
