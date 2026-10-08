package com.laundry.mqtt.service;

import java.util.UUID;

/**
 * Value object representing a parsed branch-scoped laundry MQTT topic.
 *
 * @param branchId   branch identifier — UUID of the physical branch the machine belongs to
 * @param machineId  machine identifier (e.g., "WM001")
 * @param channel    channel type (STATUS, TELEMETRY, EVENT, COMMAND, HEARTBEAT)
 */
public record ParsedMqttTopic(
    UUID branchId,
    String machineId,
    MqttChannel channel
) {}