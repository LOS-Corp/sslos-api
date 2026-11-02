package com.laundry.mqtt.service;

import com.laundry.mqtt.config.MqttConfig;
import com.laundry.mqtt.handler.AuthChannelHandler;
import com.laundry.mqtt.handler.WasherMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Dispatches incoming device messages to their handler on the MQTT worker pool,
 * off the Paho network thread.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MqttMessageRouter {

    private final MqttTopicParser topicParser;
    private final WasherMessageHandler washerHandler;
    private final AuthChannelHandler authHandler;

    @Async(MqttConfig.MQTT_EXECUTOR)
    public void route(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        topicParser.parse(topic).ifPresent(parsed -> dispatch(parsed, payload));
    }

    private void dispatch(ParsedMqttTopic topic, String payload) {
        UUID branchId = topic.branchId();
        String machineId = topic.machineId();
        MqttChannel channel = topic.channel();

        if (channel == MqttChannel.TELEMETRY || channel == MqttChannel.HEARTBEAT) {
            log.debug("MQTT RX branch={} machine={} channel={}", branchId, machineId, channel);
        } else {
            log.info("MQTT RX branch={} machine={} channel={}", branchId, machineId, channel);
        }

        try {
            switch (channel) {
                case STATUS -> washerHandler.handleStatus(branchId, machineId, payload);
                case TELEMETRY -> washerHandler.handleTelemetry(branchId, machineId, payload);
                case HEARTBEAT -> washerHandler.handleHeartbeat(branchId, machineId, payload);
                case EVENT -> washerHandler.handleEvent(branchId, machineId, payload);
                case COMMAND_ACK -> washerHandler.handleCommandAck(branchId, machineId, payload);
                case AUTH -> authHandler.handle(branchId, machineId, payload);
            }
        } catch (RuntimeException e) {
            log.error("Failed to handle MQTT message branch={} machine={} channel={}",
                    branchId, machineId, channel, e);
        }
    }
}
