package com.laundry.mqtt.service;

import com.laundry.mqtt.handler.AuthChannelHandler;
import com.laundry.mqtt.handler.WasherMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Routes incoming MQTT messages to the appropriate domain handler.
 *
 * <p>Single entry point from the Paho callback thread.
 * Dispatches asynchronously using {@code @Async("mqttTaskExecutor")}.
 * Uses {@link MqttTopicParser} to parse and validate branch-scoped topics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MqttMessageRouter {

    private final MqttTopicParser topicParser;
    private final WasherMessageHandler washerMessageHandler;
    private final AuthChannelHandler authChannelHandler;

    /**
     * Route an incoming MQTT message.
     * Called from Paho callback thread — delegates async processing immediately.
     */
    @Async("mqttTaskExecutor")
    public void route(String topic, MqttMessage message) {
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);

        Optional<ParsedMqttTopic> parsedOpt = topicParser.parse(topic);
        if (parsedOpt.isEmpty()) {
            return; // Topic parser already logged WARN
        }

        ParsedMqttTopic parsed = parsedOpt.get();
        UUID branchId = parsed.branchId();
        String machineId = parsed.machineId();
        MqttChannel channel = parsed.channel();

        if (channel == MqttChannel.TELEMETRY) {
            log.debug("MQTT RX branch={} machine={} channel={}", branchId, machineId, channel);
        } else {
            log.info("MQTT RX branch={} machine={} channel={}", branchId, machineId, channel);
        }

        try {
            switch (channel) {
                case STATUS -> washerMessageHandler.handleStatus(branchId, machineId, payload);
                case TELEMETRY -> washerMessageHandler.handleTelemetry(branchId, machineId, payload);
                case EVENT -> washerMessageHandler.handleEvent(branchId, machineId, payload);
                case COMMAND -> washerMessageHandler.handleCommandAck(branchId, machineId, payload);
                case HEARTBEAT -> washerMessageHandler.handleHeartbeat(branchId, machineId, payload);
                case AUTH -> authChannelHandler.handle(branchId, machineId, payload);
            }
        } catch (Exception e) {
            log.error("Unhandled error routing MQTT message for branch={} machine={} channel={}: {}",
                    branchId, machineId, channel, e.getMessage(), e);
        }
    }
}