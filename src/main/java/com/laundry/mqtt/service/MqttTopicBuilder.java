package com.laundry.mqtt.service;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * Builds branch-scoped topics: {@code laundry/washer/{branchId}/{machineId}/{channel}}.
 */
@Component
public class MqttTopicBuilder {

    public static final String ROOT = "laundry/washer";

    /** Every branch, machine and channel. */
    public static final String BACKEND_SUBSCRIPTION = ROOT + "/+/+/#";

    /** Backend-to-device channel. */
    public static final String COMMAND = "command";

    /** Device debug log (plain text Serial lines); for people with an MQTT client, not processed here. */
    public static final String LOG = "log";

    /** {@code laundry/washer/{branchId}/{machineId}} — the prefix of every device channel. */
    public String baseTopic(UUID branchId, String machineId) {
        Objects.requireNonNull(branchId, "branchId");
        Objects.requireNonNull(machineId, "machineId");
        return ROOT + "/" + branchId + "/" + machineId.trim();
    }

    public String commandTopic(UUID branchId, String machineId) {
        return baseTopic(branchId, machineId) + "/" + COMMAND;
    }
}
