package com.laundry.payment.entity;

/**
 * Payment method types. Payments are online-only (payOS); cash is not accepted.
 */
public enum PaymentMethod {
    ONLINE,  // Online payment via payOS
    WALLET   // In-chain credit wallet (FR-PAY-04)
}
