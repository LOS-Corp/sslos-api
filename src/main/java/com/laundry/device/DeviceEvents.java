package com.laundry.device;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

/** Sự kiện từ bo mạch, phát trong JVM cho các module nghiệp vụ (đặt lượt, locker). */
public final class DeviceEvents {

    private DeviceEvents() {
    }

    /** Bo online/offline theo topic presence. */
    public record Connectivity(String deviceId, UUID machineId, UUID lockerId, boolean online) {
    }

    /** Kênh auth: AUTH_ACK, START_REQUEST, STARTED, START_REJECTED, START_FAILED, AUTH_EXPIRED, LOAD_WEIGHED;
     *  tủ: COMPARTMENT_CLOSED, OPEN_FAILED. */
    public record Auth(String deviceId, UUID machineId, UUID lockerId, String type, JsonNode body) {
    }

    /** Bản trạng thái (status/telemetry/heartbeat): status, phase, running, remainingSeconds, runId... */
    public record Snapshot(String deviceId, UUID machineId, UUID lockerId, String channel, JsonNode body) {
    }

    /** Kênh event: STATE_CHANGED, CYCLE_STOPPED, SELF_TEST_RESULT, DOOR_OPENED... */
    public record Event(String deviceId, UUID machineId, UUID lockerId, String type, JsonNode body) {
    }

    /** Trả lời lệnh (command/ack). */
    public record CommandAck(String deviceId, UUID commandId, String command, boolean success, String reason) {
    }
}
