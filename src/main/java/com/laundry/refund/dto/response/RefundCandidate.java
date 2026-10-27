package com.laundry.refund.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An order Staff or the Owner looked up by its short code (the 8 characters on the customer's order card),
 * with the paid payments an exception refund can be raised against (UC-08 alt 4a).
 */
public record RefundCandidate(
        UUID bookingId,
        String shortCode,
        UUID branchId,
        String branchName,
        String serviceName,
        String bookingStatus,
        Instant startTime,
        String customerName,
        String customerPhone,
        List<RefundablePayment> payments) {

    /** {@code refundable} = amount − refunds already pending, approved or completed (BR-14 cap). */
    public record RefundablePayment(UUID paymentId, String method, BigDecimal amount, BigDecimal alreadyRefunded,
                                    BigDecimal refundable) {
    }
}
