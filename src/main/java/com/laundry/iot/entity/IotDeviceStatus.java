package com.laundry.iot.entity;

/**
 * Lifecycle of a physical board (machine unit with its built-in ESP32).
 *
 * <pre>
 * (USB HELLO) UNCLAIMED ──(admin approves)──→ VERIFIED ──(owner pairs)──→ PROVISIONED ──(commission)──→ ACTIVE
 * any ──(revoke / unpair)──→ REVOKED ──(admin approves again)──→ VERIFIED
 * </pre>
 *
 * <p>A device row is created the first time the board says HELLO on a USB
 * serial port of the store server, so there is no separate enrollment.
 */
public enum IotDeviceStatus {
    /** Seen on a USB port; waiting for an admin to approve it. */
    UNCLAIMED,
    /** Approved by an admin; not attached to a machine yet. */
    VERIFIED,
    /** Attached to a machine; commissioning (self-test) in progress. */
    PROVISIONED,
    /** The machine it controls has been commissioned. */
    ACTIVE,
    /** Withdrawn; ignored on the link until an admin approves it again. */
    REVOKED
}
