package com.laundry.iot.entity;

/**
 * Lifecycle of a physical ESP32 controller.
 *
 * <pre>
 * PAIRING ──(machine session)──────────────→ PROVISIONED → ACTIVE
 * PAIRING ──(device-first)──→ VERIFIED ──(attach to machine)──→ PAIRING
 * any → REVOKED → (re-scan) PAIRING
 * </pre>
 *
 * <p>A device row is created the first time an admin scans the setup QR on the
 * board's screen, so there is no separate workshop enrollment.
 */
public enum IotDeviceStatus {
    /** Pairing ended without activation (expired, cancelled or too many wrong proofs). */
    UNCLAIMED,
    /** An admin scanned the board's setup QR; waiting for the board to prove it holds the key. */
    PAIRING,
    /** Added before its machine: the board proved its code; waiting to be attached to a machine. */
    VERIFIED,
    /** Device authenticated and holds its own EMQX credentials. */
    PROVISIONED,
    /** The machine it controls has been commissioned. */
    ACTIVE,
    /** Identity withdrawn; EMQX credentials deleted. Reset the board and scan its new QR to reuse it. */
    REVOKED
}
