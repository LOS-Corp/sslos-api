package com.laundry.refund.entity;

/**
 * Refund request status
 * SRS: FR-PAY-08 - Status tracking
 */
public enum RefundStatus {
    CREATED,           // Staff tạo request
    PENDING_APPROVAL,  // Chờ Manager duyệt
    APPROVED,          // Manager đã duyệt
    REJECTED,          // Manager từ chối
    PROCESSING,        // Đang xử lý refund
    COMPLETED,         // Hoàn thành
    FAILED             // Thất bại
}
