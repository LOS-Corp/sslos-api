package com.laundry.mqtt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Service responsible for parsing and validating branch-scoped MQTT topics.
 *
 * <p>Topic contract:
 * {@code laundry/washer/{branchId-UUID}/{machineId}/{channel}}
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code laundry/washer/00000000-0000-0000-0000-000000000001/WM001/status}</li>
 *   <li>{@code laundry/washer/00000000-0000-0000-0000-000000000001/WM001/telemetry}</li>
 *   <li>{@code laundry/washer/00000000-0000-0000-0000-000000000001/WM001/event}</li>
 *   <li>{@code laundry/washer/00000000-0000-0000-0000-000000000001/WM001/command}</li>
 *   <li>{@code laundry/washer/00000000-0000-0000-0000-000000000001/WM001/command/ack}</li>
 * </ul>
 *
 * <p>The middle segment must be a syntactically valid {@link UUID}; anything else
 * (e.g. the legacy {@code BR001} brand code) is rejected so the contract stays
 * explicit.
 */
@Component
@Slf4j
public class MqttTopicParser {

    /**
     * Parses an incoming topic into a {@link ParsedMqttTopic}.
     * Returns {@link Optional#empty()} and logs a WARN on any malformed input.
     * Never throws exceptions on invalid topics to protect the MQTT listener thread.
     *
     * @param topic the raw topic string
     * @return Optional containing parsed topic, or empty if invalid
     */
    public Optional<ParsedMqttTopic> parse(String topic) {
        if (topic == null || topic.isBlank()) {
            log.warn("Cannot parse null or blank MQTT topic");
            return Optional.empty();
        }

        // Split by '/' keeping empty strings to detect empty segments like "laundry/washer//WM001/status"
        String[] parts = topic.split("/", -1);

        // Standard topic has 5 segments: ["laundry", "washer", "{branchId}", "{machineId}", "{channel}"]
        // We also gracefully support 6 segments if channel is "command/ack"
        if (parts.length < 5 || parts.length > 6) {
            log.warn("Malformed MQTT topic '{}' — unexpected number of segments ({})", topic, parts.length);
            return Optional.empty();
        }

        if (!"laundry".equalsIgnoreCase(parts[0]) || !"washer".equalsIgnoreCase(parts[1])) {
            log.warn("Malformed MQTT topic '{}' — prefix must be 'laundry/washer'", topic);
            return Optional.empty();
        }

<<<<<<< Updated upstream
        UUID branchId;
=======
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
>>>>>>> Stashed changes
        try {
            branchId = UUID.fromString(parts[2].trim());
        } catch (IllegalArgumentException e) {
            log.warn("Malformed MQTT topic '{}' — branchId segment '{}' is not a valid UUID", topic, parts[2]);
            return Optional.empty();
        }

        String machineId = parts[3].trim();
        if (machineId.isEmpty()) {
            log.warn("Malformed MQTT topic '{}' — machineId cannot be empty", topic);
            return Optional.empty();
        }

        String channelRaw = parts[4].trim().toUpperCase();
        if (parts.length == 6) {
            if ("COMMAND".equalsIgnoreCase(channelRaw) && "ACK".equalsIgnoreCase(parts[5].trim())) {
                channelRaw = "COMMAND";
            } else {
                log.warn("Malformed MQTT topic '{}' — invalid sub-channel segment '{}'", topic, parts[5]);
                return Optional.empty();
            }
        }

        MqttChannel channel;
        try {
            channel = MqttChannel.valueOf(channelRaw);
        } catch (IllegalArgumentException e) {
            log.warn("Malformed MQTT topic '{}' — invalid channel '{}'", topic, parts[4]);
            return Optional.empty();
        }

        return Optional.of(new ParsedMqttTopic(branchId, machineId, channel));
    }
}