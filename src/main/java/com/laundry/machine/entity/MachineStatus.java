package com.laundry.machine.entity;

/**
 * Operational status of a laundry machine.
 *
 * State transitions are validated by the backend — devices cannot arbitrarily
 * skip states. See MachineStateService for transition rules.
 */
public enum MachineStatus {
    /** Machine is idle and ready to accept a booking. */
    AVAILABLE,

    /** A booking has been confirmed for this machine; awaiting payment. */
    RESERVED,

    /** Payment confirmed; waiting for customer to physically start the machine. */
    WAITING_START,

    /** Machine is actively running a wash/dry cycle. */
    RUNNING,

    /** Cycle is paused (future feature). */
    PAUSED,

    /** Cycle finished normally. */
    COMPLETED,

    /** Machine is taken out of service by staff/admin (maintenance). */
    OUT_OF_SERVICE,

    /** Machine reported a hardware or software error. */
    ERROR,

    /** No heartbeat received within the configured threshold. */
    OFFLINE
}
