package com.laundry.mqtt.handler;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.laundry.machine.runtime.MachineRuntimeState;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * JSON payloads published by washer devices.
 *
 * <p>Every field is optional: a missing field stays {@code null} and is not applied
 * to the runtime state, so a light message never wipes values from a richer one.
 * Unknown fields are ignored so newer firmware never breaks parsing.
 */
public final class WasherPayloads {

    private WasherPayloads() {
    }

    /**
     * Device state snapshot, shared by {@code /status}, {@code /telemetry} and {@code /heartbeat}
     * (the firmware sends the same document on all three).
     *
     * @param error the firmware sends the error text here ({@code ""} when healthy);
     *              older firmware sends a boolean
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeviceSnapshot(
            Integer schemaVersion,
            String firmwareVersion,
            Boolean simulation,
            Long seq,
            Long sampleMillis,

            Boolean online,
            Boolean wifiConnected,
            Boolean mqttConnected,
            Integer wifiRssi,
            Long uptimeSeconds,
            Long freeHeap,

            String status,
            String phase,
            Boolean running,
            Boolean available,
            JsonNode error,
            String errorMessage,
            Boolean cycleActive,
            Integer remainingSeconds,
            Integer progressPercent,
            String motorDirection,

            @JsonAlias("weight") Double weightKg,
            @JsonAlias("waterLevel") Double waterLevelLiters,
            @JsonAlias("vibration") Double vibrationG,
            Boolean doorClosed,
            Boolean leakDetected,

            Boolean hx711Available,
            Boolean mpu6050Available,
            Boolean leakSensorEnabled,
            Boolean waterSensorEnabled
    ) {

        /** {@code online:false} or {@code status:OFFLINE}, e.g. the retained Last Will message. */
        public boolean isOffline() {
            return Boolean.FALSE.equals(online) || "OFFLINE".equalsIgnoreCase(trimmedStatus());
        }

        public String trimmedStatus() {
            return status == null ? "" : status.trim();
        }

        public MachineRuntimeState toRuntimeState() {
            MachineRuntimeState state = new MachineRuntimeState();
            state.setSchemaVersion(schemaVersion);
            state.setFirmwareVersion(firmwareVersion);
            state.setSimulation(simulation);
            state.setLastTelemetrySeq(seq);
            state.setDeviceSampleMillis(sampleMillis);

            state.setOnline(online);
            state.setWifiConnected(wifiConnected);
            state.setMqttConnected(mqttConnected);
            state.setWifiRssi(wifiRssi);
            state.setUptimeSeconds(uptimeSeconds);
            state.setFreeHeap(freeHeap);

            state.setStatus(status);
            state.setPhase(phase);
            state.setRunning(running);
            state.setAvailable(available);
            state.setError(errorFlag());
            state.setErrorMessage(errorMessage != null ? errorMessage : errorText());
            state.setCycleActive(cycleActive);
            state.setRemainingSeconds(remainingSeconds);
            state.setProgressPercent(progressPercent);
            state.setMotorDirection(motorDirection);

            state.setWeightKg(toDecimal(weightKg));
            state.setWaterLevelLiters(toDecimal(waterLevelLiters));
            state.setVibrationG(toDecimal(vibrationG));
            state.setDoorClosed(doorClosed);
            state.setLeakDetected(leakDetected);

            state.setHx711Available(hx711Available);
            state.setMpu6050Available(mpu6050Available);
            state.setLeakSensorEnabled(leakSensorEnabled);
            state.setWaterSensorEnabled(waterSensorEnabled);
            return state;
        }

        private Boolean errorFlag() {
            if (error == null || error.isNull()) {
                return null;
            }
            return error.isBoolean() ? error.booleanValue() : !error.asText().isBlank();
        }

        /** {@code "error": ""} or {@code false} means "no error" and clears it (""); absent means unknown (null). */
        private String errorText() {
            if (error == null || error.isNull()) return null;
            if (error.isTextual()) return error.asText().trim();
            return error.isBoolean() && !error.booleanValue() ? "" : null;
        }
    }

    /**
     * {@code /event}: audit/history only. Current state always comes from the snapshot channels.
     * A commissioning self-test report arrives here as {@code event = SELF_TEST_RESULT}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventPayload(
            Integer schemaVersion,
            String event,
            String status,
            String phase,
            String error,
            String errorMessage,
            String detail,
            String bookingId,
            Boolean doorClosed,
            Boolean leakDetected,

            String commandId,
            Boolean passed,
            JsonNode checks
    ) {

        public boolean isSelfTestResult() {
            return "SELF_TEST_RESULT".equalsIgnoreCase(event);
        }

        public MachineRuntimeState toRuntimeState() {
            MachineRuntimeState state = new MachineRuntimeState();
            state.setSchemaVersion(schemaVersion);
            state.setStatus(status);
            state.setPhase(phase);
            if (error != null) {
                state.setError(true);
            }
            state.setErrorMessage(errorMessage);
            state.setDoorClosed(doorClosed);
            state.setLeakDetected(leakDetected);
            return state;
        }
    }

    /**
     * {@code /command/ack}. Current firmware sends {@code commandId + success + reason};
     * older firmware used {@code requestId}, {@code result} and {@code message}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommandAckPayload(
            Integer schemaVersion,
            String command,
            String commandId,
            String requestId,
            Boolean success,
            String result,
            String message,
            String reason,
            String status,
            String phase,
            String errorMessage
    ) {

        /** The id of the {@code DeviceCommand} being acknowledged, or {@code null}. */
        public String correlationId() {
            return requestId != null && !requestId.isBlank() ? requestId : commandId;
        }

        public boolean accepted() {
            if (success != null) {
                return success;
            }
            if (result == null) {
                return false;
            }
            return switch (result.trim().toUpperCase(Locale.ROOT)) {
                case "ACCEPTED", "OK", "SUCCESS" -> true;
                default -> false;
            };
        }

        public MachineRuntimeState toRuntimeState() {
            MachineRuntimeState state = new MachineRuntimeState();
            state.setSchemaVersion(schemaVersion);
            state.setStatus(status);
            state.setPhase(phase);
            state.setErrorMessage(errorMessage);
            return state;
        }
    }

    private static BigDecimal toDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
