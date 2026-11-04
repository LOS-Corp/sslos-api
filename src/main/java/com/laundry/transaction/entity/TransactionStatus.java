package com.laundry.transaction.entity;

/**
 * Transaction status for SSLOS
 */
public enum TransactionStatus {
    PENDING_PAYMENT,  // Chờ thanh toán
    PAID,             // Đã thanh toán
    CANCELLED,        // Đã hủy
    EXPIRED           // Hết hạn
}
