package com.laundry.mqtt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Parses {@code laundry/washer/{branchId}/{machineId}/{channel}} topics.
 *
 * <p>Never throws: an invalid topic is logged and returned as empty so one bad
 * message cannot break the MQTT pipeline.
 */
@Component
@Slf4j
public class MqttTopicParser {

    private static final String PREFIX = MqttTopicBuilder.ROOT + "/";

    public Optional<ParsedMqttTopic> parse(String topic) {
        if (topic == null || !topic.startsWith(PREFIX)) {
            log.warn("Ignoring MQTT topic '{}': expected prefix '{}'", topic, PREFIX);
            return Optional.empty();
        }

        // branchId / machineId / channel (the channel itself may contain '/', e.g. command/ack)
        String[] parts = topic.substring(PREFIX.length()).split("/", 3);
        if (parts.length < 3) {
            log.warn("Ignoring MQTT topic '{}': expected {branchId}/{machineId}/{channel}", topic);
            return Optional.empty();
        }

        UUID branchId = parseUuid(parts[0]);
        if (branchId == null) {
            log.warn("Ignoring MQTT topic '{}': branchId '{}' is not a UUID", topic, parts[0]);
            return Optional.empty();
        }

        String machineId = parts[1].trim();
        if (machineId.isEmpty()) {
            log.warn("Ignoring MQTT topic '{}': machineId is empty", topic);
            return Optional.empty();
        }

        String channelPath = parts[2];
        if (MqttTopicBuilder.COMMAND.equals(channelPath) || MqttTopicBuilder.LOG.equals(channelPath)) {
            // The wildcard subscription also delivers the commands this backend publishes
            // and the devices' debug log; neither is processed here.
            return Optional.empty();
        }

        Optional<MqttChannel> channel = MqttChannel.fromPath(channelPath);
        if (channel.isEmpty()) {
            log.warn("Ignoring MQTT topic '{}': unknown channel '{}'", topic, channelPath);
            return Optional.empty();
        }

        return Optional.of(new ParsedMqttTopic(branchId, machineId, channel.get()));
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
