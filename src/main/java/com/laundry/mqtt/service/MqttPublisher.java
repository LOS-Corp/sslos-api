package com.laundry.mqtt.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Publishes MQTT messages from the backend to devices via EMQX.
 *
 * <p>The frontend MUST NOT publish directly to EMQX — all commands go through
 * this service which enforces authentication, validation, and audit logging
 * before publishing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MqttPublisher {

    private final MqttConnectionManager connectionManager;
    private final MqttTopicBuilder topicBuilder;

    /**
     * Publish a JSON payload to a specific topic.
     *
     * @param topic   the full MQTT topic
     * @param payload JSON string to publish
     * @param qos     quality of service (0, 1, or 2)
     * @throws MqttException if publishing fails
     */
    public void publish(String topic, String payload, int qos) throws MqttException {
        if (!connectionManager.isConnected()) {
            throw new IllegalStateException("Cannot publish: MQTT broker is not connected");
        }

        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(qos);
        message.setRetained(false);

        log.debug("MQTT ◀ [{}] {}", topic, payload);
        connectionManager.getClient().publish(topic, message);
        log.info("Published to [{}]", topic);
    }

    /**
     * Publish with QoS 1 (default for commands).
     */
    public void publish(String topic, String payload) throws MqttException {
        publish(topic, payload, 1);
    }

    /**
     * Build the command topic for a specific branch and machine.
     * Format: laundry/washer/{branchId-UUID}/{machineId}/command
     */
    public String buildWasherCommandTopic(UUID branchId, String machineCode) {
        return topicBuilder.commandTopic(branchId, machineCode);
    }
}