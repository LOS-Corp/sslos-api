package com.laundry.transaction.entity;

/**
 * Transaction status for SSLOS
 */
public enum TransactionStatus {
    PENDING_PAYMENT,  // Chờ thanh toán
    PAID,             // Đã thanh toán
    CONFIRMED,        // Đã xác nhận
    COMPLETED,        // Hoàn thành
    CANCELLED,        // Đã hủy
    EXPIRED           // Hết hạn
}
