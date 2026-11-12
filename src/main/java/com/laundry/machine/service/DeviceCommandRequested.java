package com.laundry.machine.service;

import java.util.UUID;

/** A device command was stored as PENDING; it is sent to the device only after the transaction commits. */
public record DeviceCommandRequested(UUID commandId, Payload payload) {

    public record Payload(String machineCode, String json) {
    }
}
