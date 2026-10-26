package com.laundry.mqtt.service;

import com.laundry.mqtt.config.MqttProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The only way the system sends commands to devices. Clients never publish to the
 * broker directly, so every command passes the backend's authorization and audit first.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MqttPublisher {

    private final MqttConnectionManager connectionManager;
    private final MqttTopicBuilder topicBuilder;
    private final MqttProperties properties;

    /**
     * Publishes a JSON payload with the configured command QoS.
     *
     * @throws IllegalStateException if the broker is not connected
     */
    public void publish(String topic, String payload) throws MqttException {
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(properties.getCommandQos());
        message.setRetained(false);

        connectionManager.client().publish(topic, message);
        log.info("MQTT TX {}", topic);
        log.debug("MQTT TX payload {}", payload);
    }

    /** {@code laundry/washer/{branchId}/{machineCode}/command} */
    public String buildWasherCommandTopic(UUID branchId, String machineCode) {
        return topicBuilder.commandTopic(branchId, machineCode);
    }
}
