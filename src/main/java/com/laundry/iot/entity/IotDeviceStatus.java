package com.laundry.iot.entity;

/**
 * Lifecycle of a physical ESP32 controller.
 *
 * <pre>
 * UNCLAIMED → PAIRING → PROVISIONED → ACTIVE
 *      ↘ LOCKED (too many wrong pairing codes)      any → REVOKED
 * </pre>
 */
public enum IotDeviceStatus {
    /** Enrolled at the workshop; not bound to any machine. */
    UNCLAIMED,
    /** An admin opened a pairing session for this device. */
    PAIRING,
    /** Device authenticated and holds its own EMQX credentials. */
    PROVISIONED,
    /** The machine it controls has been commissioned. */
    ACTIVE,
    /** Pairing locked after repeated wrong pairing codes; re-enroll to unlock. */
    LOCKED,
    /** Identity withdrawn; EMQX credentials deleted. Re-enroll to reuse the board. */
    REVOKED
}
