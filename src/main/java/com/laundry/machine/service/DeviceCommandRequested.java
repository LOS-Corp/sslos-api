package com.laundry.machine.service;

import java.util.UUID;

/** A device command was stored as PENDING; it is published to MQTT only after the transaction commits. */
public record DeviceCommandRequested(UUID commandId, Payload payload) {

    public record Payload(UUID branchId, String machineCode, String json) {
    }
}
