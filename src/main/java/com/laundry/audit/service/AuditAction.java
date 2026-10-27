package com.laundry.audit.service;

/** Sensitive actions that must leave an audit entry (NFR-14, BR-25, SRS 5.2). */
public enum AuditAction {
    // money
    REFUND_REQUESTED,
    REFUND_APPROVED,
    REFUND_REJECTED,
    WITHDRAWAL_REQUESTED,
    WITHDRAWAL_COMPLETED,
    WITHDRAWAL_REJECTED,
    // devices
    REMOTE_COMMAND,
    // permissions & accounts
    ACCOUNT_CREATED,
    ACCOUNT_UPDATED,
    ACCOUNT_STATUS_CHANGED,
    ACCOUNT_PASSWORD_RESET,
    STAFF_BRANCHES_ASSIGNED,
    // branch configuration
    BRANCH_CREATED,
    BRANCH_UPDATED,
    BRANCH_DEACTIVATED,
    // order state
    BOOKING_CANCELLED,
    BOOKING_NO_SHOW,
    BOOKING_PAYMENT_REVIEW
}
