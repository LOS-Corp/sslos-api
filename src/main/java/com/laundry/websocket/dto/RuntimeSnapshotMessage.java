package com.laundry.websocket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.runtime.MachineRuntimeState;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * WebSocket/STOMP realtime push of the merged {@link MachineRuntimeState} for
 * a single machine.
 *
 * <p>This is the canonical realtime stream the Machine Detail UI listens
 * to via {@code /topic/machines/{machineCode}/runtime} (or the
 * branch-scoped variant). The payload is full-fidelity so the frontend
 * does NOT need to poll REST endpoints for sensor data.
 *
 * <p>Field semantics match the firmware 3.1.0 contract documented in
 * {@code /docs/mqtt-contract.md}.
 */
@Schema(description = "Realtime runtime snapshot for a single machine, pushed over WebSocket/STOMP")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuntimeSnapshotMessage(
        @Schema(description = "Backend UUID of the machine (matches Machine.id)") UUID machineId,
        @Schema(description = "Human-readable code from MQTT topic", example = "WM001") String machineCode,
        @Schema(description = "Branch UUID") UUID branchId,
        @Schema(description = "Firmware version reported by the device", example = "3.1.0") String firmwareVersion,
        @Schema(description = "true if firmware is in Wokwi / mock mode") Boolean simulation,
        @Schema(description = "Firmware-side telemetry sequence number; out-of-order messages are dropped by the backend") Long seq,
        @Schema(description = "Firmware-side sample clock at the message timestamp (ms since boot)") Long deviceSampleMillis,
        @Schema(description = "True if the device is online (last heartbeat/telemetry within the threshold)") Boolean online,
        @Schema(description = "Device-reported business status", example = "AVAILABLE") String status,
        @Schema(description = "Sub-status / current cycle phase", example = "WASHING") String phase,
        @Schema(description = "Convenience: status==RUNNING") Boolean running,
        @Schema(description = "Convenience: status==AVAILABLE") Boolean available,
        @Schema(description = "Device reported an error") Boolean error,
        @Schema(description = "Human-readable error from the device") String errorMessage,
        @Schema(description = "Whether a wash/dry cycle is currently active") Boolean cycleActive,
        @Schema(description = "Seconds remaining until the cycle completes") Integer remainingSeconds,
        @Schema(description = "Cycle progress percentage (0-100)") Integer progressPercent,
        @Schema(description = "Current motor direction", example = "FORWARD") String motorDirection,
        @Schema(description = "Door switch state — false = open") Boolean doorClosed,
        @Schema(description = "Water leak sensor state — true = leak detected") Boolean leakDetected,
        @Schema(description = "Latest HX711 weight reading in kg") BigDecimal weightKg,
        @Schema(description = "Latest water level reading in liters") BigDecimal waterLevelLiters,
        @Schema(description = "Latest MPU6050 vibration reading in g") BigDecimal vibrationG,
        @Schema(description = "WiFi station connected") Boolean wifiConnected,
        @Schema(description = "MQTT broker connected") Boolean mqttConnected,
        @Schema(description = "WiFi RSSI (dBm)") Integer wifiRssi,
        @Schema(description = "Device uptime in seconds since boot") Long uptimeSeconds,
        @Schema(description = "ESP32 free heap bytes") Long freeHeap,
        @Schema(description = "HX711 sensor enabled in firmware") Boolean hx711Available,
        @Schema(description = "MPU6050 sensor enabled in firmware") Boolean mpu6050Available,
        @Schema(description = "Leak sensor enabled in firmware") Boolean leakSensorEnabled,
        @Schema(description = "Water level sensor enabled in firmware") Boolean waterSensorEnabled,
        @Schema(description = "Server timestamp of the most recent telemetry") OffsetDateTime lastTelemetryAt,
        @Schema(description = "Server timestamp of the most recent heartbeat") OffsetDateTime lastHeartbeat,
        @Schema(description = "Server timestamp of any MQTT message from the device") OffsetDateTime lastSeen,
        @Schema(description = "Server timestamp when this message was produced") OffsetDateTime timestamp
) {
    public static RuntimeSnapshotMessage from(Machine m, MachineRuntimeState s) {
        return new RuntimeSnapshotMessage(
                m.getId(),
                m.getMachineCode(),
                m.getBranchId(),
                s.getFirmwareVersion(),
                s.getSimulation(),
                s.getLastTelemetrySeq(),
                s.getDeviceSampleMillis(),
                s.getOnline() != null ? s.getOnline() : m.isOnline(),
                s.getStatus() != null ? s.getStatus()
                        : (m.getStatus() != null ? m.getStatus().name() : null),
                s.getPhase(),
                s.getRunning(),
                s.getAvailable(),
                s.getError(),
                s.getErrorMessage(),
                s.getCycleActive(),
                s.getRemainingSeconds(),
                s.getProgressPercent(),
                s.getMotorDirection(),
                s.getDoorClosed(),
                s.getLeakDetected(),
                s.getWeightKg(),
                s.getWaterLevelLiters(),
                s.getVibrationG(),
                s.getWifiConnected(),
                s.getMqttConnected(),
                s.getWifiRssi(),
                s.getUptimeSeconds(),
                s.getFreeHeap(),
                s.getHx711Available(),
                s.getMpu6050Available(),
                s.getLeakSensorEnabled(),
                s.getWaterSensorEnabled(),
                s.getLastTelemetryAt(),
                s.getLastHeartbeat(),
                s.getLastSeen(),
                OffsetDateTime.now()
        );
    }
}