package com.laundry.machine.runtime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Mutable, in-memory + persisted snapshot of a machine's runtime state.
 *
 * <p>Populated by MQTT handlers (status / telemetry / heartbeat / event / command/ack)
 * and read by the public API to enrich {@code MachineDetailDto} with realtime data.
 *
 * <p>Fields intentionally mirror the firmware payload — see {@code WasherPayloads}.
 * Anything the firmware does not send yet is left {@code null} (frontend handles
 * nullable fields gracefully).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MachineRuntimeState implements Serializable {

    /** Logical key: matches {@code machines.id}. UUID, not branchId+machineCode. */
    private UUID machineId;

    /** Denormalized for cheap broadcasting/lookup. */
    private UUID branchId;
    private String machineCode;

    // ── Connectivity & device health ────────────────────────────────────────
    private Boolean online;
    private Boolean wifiConnected;
    private Boolean mqttConnected;
    private Integer wifiRssi;
    private Long uptimeSeconds;
    private Long freeHeap;

    // ── Operational status from firmware ───────────────────────────────────
    /** Device-reported business status (AVAILABLE, WASHING, …). */
    private String status;
    /** Sub-status / current phase (IDLE, WASHING, SPINNING, …). */
    private String phase;
    private Boolean running;
    private Boolean available;
    private Boolean error;
    private String errorMessage;

    // ── Cycle progress ─────────────────────────────────────────────────────
    private Boolean cycleActive;
    private Integer remainingSeconds;
    private Integer progressPercent;
    private String motorDirection;

    // ── Sensor readings (latest known) ─────────────────────────────────────
    /** Preferred field — weight in kg from firmware. */
    private BigDecimal weightKg;
    /** Legacy fallback from older firmware (was {@code "weight"} in kg). */
    @JsonIgnore
    private BigDecimal weightKgCompat;
    private BigDecimal waterLevelLiters;
    /** Preferred field — vibration in g from firmware. */
    private BigDecimal vibrationG;
    /** Legacy fallback from older firmware (was {@code "vibration"} in g). */
    @JsonIgnore
    private BigDecimal vibrationGCompat;
    private Boolean doorClosed;
    private Boolean leakDetected;

    // ── Sensor availability flags (firmware capability) ────────────────────
    private Boolean hx711Available;
    private Boolean mpu6050Available;
    private Boolean leakSensorEnabled;
    private Boolean waterSensorEnabled;

    // ── Bookkeeping ────────────────────────────────────────────────────────
    /** Firmware version reported by the device (e.g. {@code "3.1.0"}). */
    private String firmwareVersion;
    /** Whether the device is running in simulation mode (Wokwi / mock). */
    private Boolean simulation;
    /** Last seen telemetry seq. Used to reject out-of-order / stale messages. */
    private Long lastTelemetrySeq;
    /** Device-side sample clock at the last telemetry message (ms). For debugging only. */
    private Long deviceSampleMillis;

    /** Server timestamp of the last received {@code /heartbeat} from the device. */
    private OffsetDateTime lastHeartbeat;
    /** Server timestamp of the last received {@code /telemetry} from the device. */
    private OffsetDateTime lastTelemetryAt;
    /** Set on every update — used as {@code lastSeen} by the API. */
    private OffsetDateTime lastSeen;
    /** Set only when {@code status} actually changes — useful for transitions. */
    private OffsetDateTime lastStatusChange;
    /** Last seen firmware schema version (best-effort). */
    private Integer schemaVersion;
}