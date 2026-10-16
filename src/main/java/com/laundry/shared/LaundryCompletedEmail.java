package com.laundry.shared;

import java.math.BigDecimal;
import java.time.Instant;

/** Data for the "your laundry is done" notification. */
public record LaundryCompletedEmail(
        String toEmail,
        String customerName,
        String bookingCode,
        String serviceName,
        String branchName,
        String branchAddress,
        String machineCodes,
        Instant startedAt,
        Instant completedAt,
        BigDecimal totalAmount
) {}
