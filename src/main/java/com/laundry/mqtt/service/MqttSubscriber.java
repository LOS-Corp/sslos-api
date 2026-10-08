package com.laundry.mqtt.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.stereotype.Service;

/**
 * Manages MQTT topic subscriptions.
 *
 * <p>All subscriptions are defined here. This class is called:
 * <ol>
 *   <li>On initial connection (from {@link MqttConnectionManager#connect()})
 *   <li>After every reconnect (from {@link MqttConnectionManager#connectComplete()})
 * </ol>
 *
 * <p>Wildcard subscriptions cover all machines, enabling automatic
 * discovery of new devices without code changes.
 *
 * <p>Topic structure: {@code laundry/{deviceType}/{branchId-UUID}/{machineId}/{messageType}}
 * Future extension: dryer, locker topics can be added here without touching
 * the washer handler.
 */
@Service
@Slf4j
public class MqttSubscriber {

    /** QoS 1 for all incoming subscriptions: at-least-once delivery. */
    private static final int SUBSCRIBE_QOS = 1;

    /**
     * Subscribe to all configured topics on the given client.
     * Uses the multi-branch wildcard: laundry/washer/+/+/# — matches every
     * branchId (UUID), every machineId, and every channel.
     * Safe to call multiple times (idempotent subscribe operation).
     */
    public void subscribeAll(MqttClient client) {
        log.info("Subscribing to branch-scoped MQTT wildcard: {}", MqttTopics.BACKEND_SUBSCRIPTION);
        subscribe(client, MqttTopics.BACKEND_SUBSCRIPTION);
        log.info("✅ Subscribed to {}", MqttTopics.BACKEND_SUBSCRIPTION);
    }

    private void subscribe(MqttClient client, String topic) {
        try {
            client.subscribe(topic, SUBSCRIBE_QOS);
            log.debug("  ✓ Subscribed: {}", topic);
        } catch (MqttException e) {
            log.error("Failed to subscribe to topic '{}': {}", topic, e.getMessage(), e);
        }
    }

    /**
     * Extracts the machineId segment from an MQTT topic.
     *
     * <p>Topic format: {@code laundry/{type}/{branchId-UUID}/{machineId}/{messageType}}
     * Segment indices: 0=laundry, 1=type, 2=branchId, 3=machineId, 4=messageType
     *
     * @param topic the full MQTT topic string
     * @return the machineId segment, or null if topic format is unexpected
     */
    public static String extractMachineId(String topic) {
        if (topic == null) return null;
        String[] parts = topic.split("/");
        if (parts.length >= 4) {
            return parts[3];
        }
        return null;
    }

    /**
     * Extracts the device type segment from an MQTT topic.
     * e.g., "washer", "dryer", "locker"
     */
    public static String extractDeviceType(String topic) {
        if (topic == null) return null;
        String[] parts = topic.split("/");
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }

    /**
     * Extracts the branchId (UUID) segment from an MQTT topic.
     */
    public static String extractBranchId(String topic) {
        if (topic == null) return null;
        String[] parts = topic.split("/");
        if (parts.length >= 3) {
            return parts[2];
        }
        return null;
    }

    /**
     * Extracts the message type segment from an MQTT topic.
     * e.g., "status", "telemetry", "heartbeat", "event", "command/ack"
     */
    public static String extractMessageType(String topic) {
        if (topic == null) return null;
        String[] parts = topic.split("/");
        if (parts.length >= 5) {
            // For "command/ack" return the combined remainder
            StringBuilder sb = new StringBuilder(parts[4]);
            for (int i = 5; i < parts.length; i++) {
                sb.append("/").append(parts[i]);
            }
            return sb.toString();
        }
        return null;
    }
}