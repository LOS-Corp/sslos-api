package com.laundry.machine.entity;

/**
 * Types of device events that are persisted in the device_events table.
 *
 * <p>Events originate either from the device (via MQTT /event topic) or from
 * the backend itself (e.g., OFFLINE detected by scheduler).
 *
 * <p>Historical note: an earlier version of this enum carried an
 * {@code ESTOP} value tied to a physical STOP button. The ESP32 firmware v2
 * no longer ships a physical STOP button, so {@code ESTOP} was removed.
 * Use {@link #STOP_COMMAND} for the audit trail of remote STOP commands
 * and {@link #DEVICE_ERROR} for genuine safety errors (DOOR OPEN, LEAK
 * DETECTED, OVERLOAD, EXCESSIVE VIBRATION, …).
 */
public enum DeviceEventType {
    // ── Device-originated events ────────────────────────────────────────────
    /** Machine cycle started successfully. */
    STARTED,

    /** Machine cycle completed normally. */
    COMPLETED,

    /** Remote STOP command accepted by the device; cycle cancelled cleanly. */
    STOP_COMMAND,

    /** Door was opened during or between cycles. */
    DOOR_OPENED,

    /** Door was closed. */
    DOOR_CLOSED,

    /** Water leak sensor triggered. */
    LEAK_DETECTED,

    /** Load exceeds safe weight threshold. */
    OVERLOAD,

    /** Excessive vibration detected by IMU. */
    EXCESSIVE_VIBRATION,

    /** General device hardware/software error. */
    DEVICE_ERROR,

    /** Device successfully reconnected after being offline. */
    CONNECTION_RECOVERED,

    // ── Backend-originated events ───────────────────────────────────────────
    /** Backend scheduler determined the machine is offline (heartbeat timeout). */
    OFFLINE_DETECTED,

    /** A command was sent to the device. */
    COMMAND_SENT,

    /** A command was acknowledged by the device. */
    COMMAND_ACKNOWLEDGED,

    /** A command was rejected by the device. */
    COMMAND_REJECTED,

    /** A command timed out without acknowledgement. */
    COMMAND_TIMEOUT,

    /** Machine status changed (generic transition event). */
    STATUS_CHANGED,

    /** New machine registered in the system (admin action). */
    MACHINE_REGISTERED,

    // ── Provisioning / commissioning ────────────────────────────────────────
    /** Admin scanned the device QR and opened a pairing session. */
    DEVICE_PAIRING_STARTED,

    /** Device authenticated and received its own EMQX identity. */
    DEVICE_PROVISIONED,

    /** Device reported a passing commissioning self-test. */
    SELF_TEST_PASSED,

    /** Device reported a failing commissioning self-test. */
    SELF_TEST_FAILED,

    /** Admin commissioned the machine; it is now bookable. */
    MACHINE_COMMISSIONED,

    /** Device identity revoked and its EMQX credentials removed. */
    DEVICE_REVOKED
}