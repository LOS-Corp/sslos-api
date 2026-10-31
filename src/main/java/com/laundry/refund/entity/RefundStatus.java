package com.laundry.refund.entity;

/**
 * Refund lifecycle. APPROVED is not COMPLETED: money has moved only when the refund is COMPLETED
 * (BR-14). CREATED is kept only for rows written before V41; new requests start in PENDING_APPROVAL.
 */
public enum RefundStatus {
    CREATED,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    PROCESSING,
    COMPLETED,
    FAILED
}
