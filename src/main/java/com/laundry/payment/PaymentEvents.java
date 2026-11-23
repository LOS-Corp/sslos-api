package com.laundry.payment;

import java.util.UUID;

/** Phát đồng bộ trong transaction ghi nhận tiền; module đơn xử lý (xác nhận đơn, kích hoạt máy, thêm phút sấy). */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    /** Phiếu thu đã trả đủ (ví + payOS). */
    public record IntentPaid(UUID intentId, UUID orderId, PaymentIntent.Purpose purpose) {
    }

    /** Phiếu thu hết hạn mà chưa trả đủ. */
    public record IntentExpired(UUID intentId, UUID orderId, PaymentIntent.Purpose purpose) {
    }
}
