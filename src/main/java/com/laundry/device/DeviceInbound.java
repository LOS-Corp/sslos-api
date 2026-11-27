package com.laundry.device;

import com.laundry.locker.LockerCompartmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Tin của bo đã gắn máy/tủ: lưu trạng thái, ghi nhật ký, rồi phát {@link DeviceEvents} cho module nghiệp vụ.
 * Chạy trên luồng mqtt-link, mỗi tin một transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceInbound {

    private final DeviceRepository devices;
    private final DeviceCommandRepository commands;
    private final LockerCompartmentRepository compartments;
    private final DeviceMessageLog messageLog;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional
    public void route(String deviceId, String channel, String payload) {
        Optional<Device> found = devices.findById(deviceId);
        if (found.isEmpty() || found.get().getLinkState() != Device.LinkState.BOUND) return;
        Device d = found.get();
        JsonNode body;
        try {
            body = mapper.readTree(payload);
        } catch (JacksonException e) {
            return;
        }
        UUID machineId = d.getMachineId();
        UUID lockerId = d.getLockerId();
        Instant now = clock.instant();
        switch (channel) {
            case "status", "telemetry", "heartbeat" -> {
                devices.saveStatus(deviceId, payload, now);
                if ("status".equals(channel)) messageLog.record(deviceId, channel, text(body, "status"), payload);
                if (lockerId != null) updateCompartments(lockerId, body, now);
                events.publishEvent(new DeviceEvents.Snapshot(deviceId, machineId, lockerId, channel, body));
            }
            case "event" -> {
                String type = upper(text(body, "type") != null ? text(body, "type") : text(body, "event"));
                messageLog.record(deviceId, channel, type, payload);
                events.publishEvent(new DeviceEvents.Event(deviceId, machineId, lockerId, type, body));
            }
            case "auth" -> {
                String type = upper(text(body, "type"));
                messageLog.record(deviceId, channel, type, payload);
                events.publishEvent(new DeviceEvents.Auth(deviceId, machineId, lockerId, type, body));
            }
            case "command/ack" -> {
                messageLog.record(deviceId, channel, upper(text(body, "command")), payload);
                onAck(deviceId, body, payload, now);
            }
            default -> log.debug("[DEVICE] {} channel {} ignored", deviceId, channel);
        }
    }

    private void onAck(String deviceId, JsonNode body, String payload, Instant now) {
        UUID id = uuid(text(body, "commandId"));
        boolean success = body.path("success").asBoolean(false);
        String reason = text(body, "reason");
        if (id != null) {
            commands.findById(id).filter(c -> c.getDeviceId().equals(deviceId)).ifPresent(c -> {
                c.setStatus(success ? DeviceCommand.Status.ACKED : DeviceCommand.Status.FAILED);
                c.setAckedAt(now);
                c.setResult(payload);
            });
        }
        events.publishEvent(new DeviceEvents.CommandAck(deviceId, id, upper(text(body, "command")), success, reason));
    }

    private void updateCompartments(UUID lockerId, JsonNode body, Instant now) {
        JsonNode list = body.path("compartments");
        if (!list.isArray()) return;
        for (JsonNode c : list) {
            int no = c.path("no").asInt(0);
            if (no < 1) continue;
            compartments.findByLockerIdAndCompartmentNo(lockerId, no).ifPresent(comp -> {
                comp.setDoorClosed(c.path("doorClosed").asBoolean(true));
                comp.setUnlocked(c.path("unlocked").asBoolean(false));
                comp.setStateUpdatedAt(now);
            });
        }
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asString();
    }

    static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    static UUID uuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
