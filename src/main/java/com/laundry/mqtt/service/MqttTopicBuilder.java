package com.laundry.mqtt.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Centralized topic builder for constructing branch-scoped MQTT topics.
 *
 * <p>Format: {@code laundry/washer/{branchId}/{machineId}/{channel}}
 *
 * <p>The middle segment is the {@link UUID} of the branch where the machine is
 * physically installed. This uniquely partitions MQTT traffic per branch and
 * keeps the multi-tenant scope intact while removing the multi-brand concept.
 */
@Component
public class MqttTopicBuilder {

<<<<<<< Updated upstream
    /** {@code laundry/washer/{branchId}/{machineId}} — the prefix every device channel hangs off. */
=======
    public static final String ROOT = "laundry/washer";

    /** Every branch, machine and channel. */
    public static final String BACKEND_SUBSCRIPTION = ROOT + "/+/+/#";

    /** Backend-to-device channel. */
    public static final String COMMAND = "command";

    /** Device debug log (plain text Serial lines); for people with an MQTT client, not processed here. */
    public static final String LOG = "log";

    /** {@code laundry/washer/{branchId}/{machineId}} — the prefix of every device channel. */
>>>>>>> Stashed changes
    public String baseTopic(UUID branchId, String machineId) {
        return MqttTopics.ROOT + "/" + branchId + "/" + machineId.trim();
    }

    public String commandTopic(UUID branchId, String machineId) {
        return build(branchId, machineId, "command");
    }

    public String statusTopic(UUID branchId, String machineId) {
        return build(branchId, machineId, "status");
    }

    public String telemetryTopic(UUID branchId, String machineId) {
        return build(branchId, machineId, "telemetry");
    }

    public String eventTopic(UUID branchId, String machineId) {
        return build(branchId, machineId, "event");
    }

    public String heartbeatTopic(UUID branchId, String machineId) {
        return build(branchId, machineId, "heartbeat");
    }

    public String machineWildcard(UUID branchId, String machineId) {
        return build(branchId, machineId, "#");
    }

    /**
     * Branch-scoped wildcard — covers every machine and channel of a single branch.
     */
    public String branchWildcard(UUID branchId) {
        return MqttTopics.ROOT + "/" + (branchId != null ? branchId.toString() : "+") + "/#";
    }

    private String build(UUID branchId, String machineId, String channel) {
        String safeBranch = (branchId != null) ? branchId.toString() : "+";
        String safeMachine = (machineId != null && !machineId.isBlank()) ? machineId.trim() : "+";
        return MqttTopics.ROOT + "/" + safeBranch + "/" + safeMachine + "/" + channel;
    }
}