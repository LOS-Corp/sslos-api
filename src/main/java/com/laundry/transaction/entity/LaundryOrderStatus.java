package com.laundry.transaction.entity;

/**
 * Laundry service order status
 */
public enum LaundryOrderStatus {
    PENDING,      // Chờ nhận đồ
    RECEIVED,     // Đã nhận đồ
    PROCESSING,   // Đang xử lý
    READY,       // Sẵn sàng trả đồ
    COMPLETED,    // Hoàn thành
    CANCELLED    // Đã hủy
}
