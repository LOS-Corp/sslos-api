package com.laundry.mqtt.service;

import java.util.UUID;

/**
 * A validated device topic: {@code laundry/washer/{branchId}/{machineId}/{channel}}.
 *
 * @param machineId the machine code, e.g. {@code WM001}
 */
public record ParsedMqttTopic(UUID branchId, String machineId, MqttChannel channel) {
}
