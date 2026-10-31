package com.laundry.payment.gateway;

import java.math.BigDecimal;

public record GatewayPaymentStatus(
        String transactionReference,
        String status,
        BigDecimal amount
) {}
