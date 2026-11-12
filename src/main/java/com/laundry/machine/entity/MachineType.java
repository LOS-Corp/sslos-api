package com.laundry.machine.entity;

/**
 * Type of laundry machine.
 * Extensible for future device types.
 */
public enum MachineType {
    WASHER,
    DRYER,
    /** Smart locker cabinet; its compartments are LOCKER rows in service_resources. */
    LOCKER
}
