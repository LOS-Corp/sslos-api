package com.laundry.machine.entity;

/**
 * Status of a command sent from the backend to a device.
 *
 * Lifecycle:
 *   PENDING → SENT → ACKNOWLEDGED / REJECTED / TIMEOUT / FAILED
 */
public enum DeviceCommandStatus {
    /** Command created in DB but not yet published to MQTT broker. */
    PENDING,

    /** Command published to MQTT. Awaiting ACK from device. */
    SENT,

    /** Device acknowledged the command (accepted it for execution). */
    ACKNOWLEDGED,

    /** Device explicitly rejected the command (safety interlock, wrong state, etc.). */
    REJECTED,

    /** No ACK received within the timeout window. */
    TIMEOUT,

    /** Backend-side failure before or during publish. */
    FAILED
}
