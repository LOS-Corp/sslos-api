package com.laundry.machine.entity;

/**
 * Deployment (commissioning) lifecycle of a machine, independent of its
 * runtime {@link MachineStatus}. Only {@link #ACTIVE} machines can be booked
 * or checked in.
 *
 * <pre>
 * UNASSIGNED → PAIRING → PROVISIONED → TESTING → TEST_PASSED → ACTIVE
 *                                          ↘ TEST_FAILED ↗ (re-test)
 * </pre>
 */
public enum MachineDeploymentStatus {
    /** Machine record exists; no ESP32 bound to it. */
    UNASSIGNED,

    /** Admin scanned the device QR; a time-limited pairing session is open. */
    PAIRING,

    /** Device proved its identity and received its own EMQX credentials. */
    PROVISIONED,

    /** SELF_TEST command sent; waiting for the device result. */
    TESTING,

    /** Device reported a failing self-test. */
    TEST_FAILED,

    /** Device reported a passing self-test; awaiting admin commissioning. */
    TEST_PASSED,

    /** Commissioned: machine may take part in booking and check-in. */
    ACTIVE
}
