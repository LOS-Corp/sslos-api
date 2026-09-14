package com.laundry.transaction.entity;

/**
 * Drop-off locker reservation status
 */
public enum LockerReservationStatus {
    RESERVED,   // Đã đặt
    CONFIRMED,  // Đã xác nhận (thanh toán thành công)
    USED,       // Đã sử dụng
    EXPIRED,    // Hết hạn
    CANCELLED   // Đã hủy
}
