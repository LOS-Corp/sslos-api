package com.laundry.refund.dto.request;

import jakarta.validation.constraints.Size;

/** Owner decision on an exception refund; {@code reason} is required to reject (BR-25 spirit: decisions are explained). */
public record RefundDecisionRequest(@Size(max = 1000) String reason) {
}
