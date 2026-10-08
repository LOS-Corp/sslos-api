package com.laundry.mqtt.handler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.laundry.machine.runtime.MachineRuntimeState;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * MQTT payload DTOs for messages received from washer devices.
 *
 * <p>Firmware compatibility:
 * <ul>
 *     <li>Schema v2 is the current contract. Legacy v1 fields are still
 *         parsed but ignored when v2 fields are present.</li>
 *     <li>{@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps the
 *         forward-compatibility contract — the backend never refuses a
 *         message just because the firmware added a new field.</li>
 * </ul>
 *
 * <p>Mapping rules (firmware → runtime snapshot):
 * <ul>
 *     <li>{@code weightKg} preferred; falls back to legacy {@code weight}.</li>
 *     <li>{@code vibrationG} preferred; falls back to legacy {@code vibration}.</li>
 *     <li>{@code waterLevelLiters} preferred; legacy {@code waterLevel} kept
 *         as fallback for older firmware.</li>
 *     <li>All sensor fields are <strong>optional</strong>. A heartbeat that
 *         omits {@code weightKg} must NOT zero the previous value.</li>
 * </ul>
 *
 * <p>Topic contract: {@code laundry/washer/{branchId-UUID}/{machineId}/{channel}}.
 */
public class WasherPayloads {

    // ── /status ────────────────────────────────────────────────────────────
    /**
     * Payload for {@code laundry/washer/{branchId-UUID}/{machineId}/status}.
     *
     * <p>Status is retained on the broker when the device uses MQTT Last Will —
     * offline detection relies on a retained {@code "OFFLINE"} status arriving
     * with {@code "online": false}.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StatusPayload {
        private Integer schemaVersion;
        private String firmwareVersion;
        private Boolean simulation;
        private Long seq;
        private Long sampleMillis;
        private UUID branchId;
        private String machineId;
        private String machineType;
        private Boolean online;
        private String status;
        private String phase;
        private Boolean running;
        private Boolean available;
        private Boolean error;
        private String errorMessage;
        private Boolean cycleActive;
        private Boolean doorClosed;
        private Boolean leakDetected;
        private Integer wifiRssi;

        public MachineRuntimeState toRuntimeSnapshot() {
            MachineRuntimeState s = new MachineRuntimeState();
            s.setSchemaVersion(schemaVersion);
            s.setFirmwareVersion(firmwareVersion);
            s.setSimulation(simulation);
            s.setLastTelemetrySeq(seq);
            s.setDeviceSampleMillis(sampleMillis);
            s.setOnline(online);
            s.setStatus(status);
            s.setPhase(phase);
            s.setRunning(running);
            s.setAvailable(available);
            s.setError(error);
            s.setErrorMessage(errorMessage);
            s.setCycleActive(cycleActive);
            s.setDoorClosed(doorClosed);
            s.setLeakDetected(leakDetected);
            s.setWifiRssi(wifiRssi);
            return s;
        }
    }

    // ── /telemetry ─────────────────────────────────────────────────────────
    /**
     * Payload for {@code laundry/washer/{branchId-UUID}/{machineId}/telemetry}.
     *
     * <p>Telemetry is the <strong>comprehensive runtime snapshot</strong>. The
     * firmware sends it every 2 s when running and every 5 s when idle. The
     * backend treats this as authoritative for every field it carries.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TelemetryPayload {
        private Integer schemaVersion;
        private String firmwareVersion;
        private Boolean simulation;
        private Long seq;
        private Long sampleMillis;
        private UUID branchId;
        private String machineId;
        private String machineType;

        // ── Connectivity ──
        private Boolean online;
        private Boolean wifiConnected;
        private Boolean mqttConnected;
        private Integer wifiRssi;
        private Long uptimeSeconds;
        private Long freeHeap;

        // ── Operational state ──
        private String status;
        private String phase;
        private Boolean running;
        private Boolean available;
        private Boolean error;
        private String errorMessage;
        private Boolean cycleActive;
        private Integer remainingSeconds;
        private Integer progressPercent;
        private String motorDirection;

        // ── Sensors (preferred names) ──
        private Double weightKg;
        private Double waterLevelLiters;
        private Double vibrationG;
        private Boolean doorClosed;
        private Boolean leakDetected;

        // ── Sensors (legacy fallback) ──
        @JsonProperty("weight")
        private Double weightLegacy;
        @JsonProperty("waterLevel")
        private Double waterLevelLegacy;
        @JsonProperty("vibration")
        private Double vibrationLegacy;

        // ── Sensor capability flags ──
        private Boolean hx711Available;
        private Boolean mpu6050Available;
        private Boolean leakSensorEnabled;
        private Boolean waterSensorEnabled;

        public MachineRuntimeState toRuntimeSnapshot() {
            MachineRuntimeState out = new MachineRuntimeState();
            out.setSchemaVersion(schemaVersion);
            out.setFirmwareVersion(firmwareVersion);
            out.setSimulation(simulation);
            out.setLastTelemetrySeq(seq);
            out.setDeviceSampleMillis(sampleMillis);
            out.setOnline(online);
            out.setWifiConnected(wifiConnected);
            out.setMqttConnected(mqttConnected);
            out.setWifiRssi(wifiRssi);
            out.setUptimeSeconds(uptimeSeconds);
            out.setFreeHeap(freeHeap);

            out.setStatus(status);
            out.setPhase(phase);
            out.setRunning(running);
            out.setAvailable(available);
            out.setError(error);
            out.setErrorMessage(errorMessage);
            out.setCycleActive(cycleActive);
            out.setRemainingSeconds(remainingSeconds);
            out.setProgressPercent(progressPercent);
            out.setMotorDirection(motorDirection);

            // weightKg wins, then legacy weight
            Double weight = weightKg != null ? weightKg : weightLegacy;
            if (weight != null) {
                out.setWeightKg(java.math.BigDecimal.valueOf(weight));
            } else if (weightKg == null && weightLegacy != null) {
                out.setWeightKgCompat(java.math.BigDecimal.valueOf(weightLegacy));
            }

            // waterLevelLiters wins, then legacy waterLevel
            Double level = waterLevelLiters != null ? waterLevelLiters : waterLevelLegacy;
            if (level != null) {
                out.setWaterLevelLiters(java.math.BigDecimal.valueOf(level));
            }

            // vibrationG wins, then legacy vibration
            Double vibration = vibrationG != null ? vibrationG : vibrationLegacy;
            if (vibration != null) {
                out.setVibrationG(java.math.BigDecimal.valueOf(vibration));
            } else if (vibrationG == null && vibrationLegacy != null) {
                out.setVibrationGCompat(java.math.BigDecimal.valueOf(vibrationLegacy));
            }

            out.setDoorClosed(doorClosed);
            out.setLeakDetected(leakDetected);

            out.setHx711Available(hx711Available);
            out.setMpu6050Available(mpu6050Available);
            out.setLeakSensorEnabled(leakSensorEnabled);
            out.setWaterSensorEnabled(waterSensorEnabled);

            return out;
        }
    }

    // ── /heartbeat ─────────────────────────────────────────────────────────
    /**
     * Payload for {@code laundry/washer/{branchId-UUID}/{machineId}/heartbeat}.
     *
     * <p>Firmware sends this every ~10 s. Touches only connectivity + status
     * fields; sensor readings are NOT reset.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class HeartbeatPayload {
        private Integer schemaVersion;
        private String firmwareVersion;
        private Boolean simulation;
        private Long seq;
        private Long sampleMillis;
        private UUID branchId;
        private String machineId;
        private Boolean wifiConnected;
        private Boolean mqttConnected;
        private Integer wifiRssi;
        private Long uptimeSeconds;
        private Long freeHeap;
        private String status;
        private String phase;
        private Boolean running;
        private Boolean available;
        private Boolean error;
        private String errorMessage;
        private Boolean hx711Available;
        private Boolean mpu6050Available;
        private Boolean leakSensorEnabled;
        private Boolean waterSensorEnabled;

        public MachineRuntimeState toRuntimeSnapshot() {
            MachineRuntimeState s = new MachineRuntimeState();
            s.setSchemaVersion(schemaVersion);
            s.setFirmwareVersion(firmwareVersion);
            s.setSimulation(simulation);
            s.setLastTelemetrySeq(seq);
            s.setDeviceSampleMillis(sampleMillis);
            s.setWifiConnected(wifiConnected);
            s.setMqttConnected(mqttConnected);
            s.setWifiRssi(wifiRssi);
            s.setUptimeSeconds(uptimeSeconds);
            s.setFreeHeap(freeHeap);
            s.setStatus(status);
            s.setPhase(phase);
            s.setRunning(running);
            s.setAvailable(available);
            s.setError(error);
            s.setErrorMessage(errorMessage);
            s.setHx711Available(hx711Available);
            s.setMpu6050Available(mpu6050Available);
            s.setLeakSensorEnabled(leakSensorEnabled);
            s.setWaterSensorEnabled(waterSensorEnabled);
            return s;
        }
    }

    // ── /event ─────────────────────────────────────────────────────────────
    /**
     * Payload for {@code laundry/washer/{branchId-UUID}/{machineId}/event}.
     *
     * <p>Events are used for audit/history only — the authoritative current
     * state always comes from /telemetry or /status.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EventPayload {
        private Integer schemaVersion;
        private UUID branchId;
        private String machineId;
        private String event;       // DEVICE_ONLINE, CYCLE_STARTED, CYCLE_COMPLETED, CYCLE_STOPPED, ERROR, …
        private String status;
        private String phase;
        private String error;
        private String errorMessage;
        private String detail;
        private String bookingId;
        private Boolean doorClosed;
        private Boolean leakDetected;

        public MachineRuntimeState toRuntimeSnapshot() {
            MachineRuntimeState s = new MachineRuntimeState();
            s.setSchemaVersion(schemaVersion);
            s.setStatus(status);
            s.setPhase(phase);
            if (error != null) s.setError(true);
            s.setErrorMessage(errorMessage);
            s.setDoorClosed(doorClosed);
            s.setLeakDetected(leakDetected);
            return s;
        }
    }

    // ── /command/ack ───────────────────────────────────────────────────────
    /**
     * Payload for {@code laundry/washer/{branchId-UUID}/{machineId}/command/ack}.
     *
* <p>Backend matches ACKs to the original {@link com.laundry.machine.entity.DeviceCommand#getCommandId()}
 * or the firmware's {@code requestId}. The legacy {@code commandId} field is
 * also supported for backward compatibility with WM001 firmware v1.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CommandAckPayload {
        private Integer schemaVersion;
        private UUID branchId;
        private String machineId;
        private String command;
        private String commandId;     // legacy
        private String requestId;     // firmware v2
        private Boolean success;      // firmware v2
        private String result;        // legacy: ACCEPTED, REJECTED, …
        private String message;       // firmware v2 human-readable
        private String reason;        // legacy
        private String status;        // device-reported current status after the command
        private String phase;
        private String errorMessage;
        private String timestamp;

        /** Returns whichever correlation id the firmware provided, or {@code null}. */
        public String correlationId() {
            if (requestId != null && !requestId.isBlank()) return requestId;
            return commandId;
        }

        public boolean accepted() {
            if (success != null) return success;
            if (result != null) {
                String r = result.trim().toUpperCase();
                return "ACCEPTED".equals(r) || "OK".equals(r) || "SUCCESS".equals(r);
            }
            return false;
        }

        public MachineRuntimeState toRuntimeSnapshot() {
            MachineRuntimeState s = new MachineRuntimeState();
            s.setSchemaVersion(schemaVersion);
            s.setStatus(status);
            s.setPhase(phase);
            s.setErrorMessage(errorMessage);
            return s;
        }
    }
}