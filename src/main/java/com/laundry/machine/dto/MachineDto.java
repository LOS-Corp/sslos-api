package com.laundry.machine.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laundry.machine.entity.MachineDeploymentStatus;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.runtime.MachineRuntimeState;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Aggregate DTO returned by Machine Detail / list endpoints.
 *
 * <p>This is the single API payload the frontend needs to display a
 * machine: static registry info (machineCode, branch, type, displayName, …)
 * <strong>merged</strong> with the latest runtime snapshot (online, status,
 * phase, weightKg, vibrationG, cycle progress, WiFi RSSI, …).
 *
 * <p>Top-level {@link #online}/{@link #status}/{@link #lastHeartbeatAt}
 * mirror the persisted {@code machines} row; the {@link #runtime} block
 * carries the high-frequency fields without bloating the entity.
 *
 * <p>For backward compatibility, the top-level {@code weightKg},
 * {@code waterLevelLiters}, {@code vibrationG} and {@code remainingSeconds}
 * are duplicated as flattened convenience accessors.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MachineDto {
    private UUID id;
    private String machineCode;
    private UUID branchId;
    private MachineType type;
    private MachineStatus status;
    /** Commissioning lifecycle; only ACTIVE machines are bookable. */
    private MachineDeploymentStatus deploymentStatus;
    /** Bound ESP32 identity, null when unassigned. */
    private String deviceId;
    private boolean online;
    private OffsetDateTime lastHeartbeatAt;

    /** Latest runtime snapshot — full structure including sensor data, cycle, phase. */
    private MachineRuntimeState runtime;

    // ── Convenience accessors mirrored from runtime for legacy clients ──────
    private BigDecimal currentWeight;
    private BigDecimal weightKg;
    private BigDecimal waterLevelLiters;
    private BigDecimal vibrationG;
    private Integer remainingSeconds;
    private Integer progressPercent;
    private Boolean cycleActive;
    private String phase;
    private String motorDirection;
    private Boolean doorClosed;
    private Boolean leakDetected;
    private Integer wifiRssi;
    private Long uptimeSeconds;

    private String errorCode;
    private String displayName;
    private String locationDescription;
    private java.math.BigDecimal capacityKg;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /**
     * Project flattened legacy fields onto the DTO for callers that don't
     * read {@code .runtime}. Called by the controller after building the DTO.
     */
    public void flattenRuntime() {
        if (runtime == null) return;
        if (weightKg == null) weightKg = runtime.getWeightKg();
        if (currentWeight == null) currentWeight = runtime.getWeightKg();
        if (waterLevelLiters == null) waterLevelLiters = runtime.getWaterLevelLiters();
        if (vibrationG == null) vibrationG = runtime.getVibrationG();
        if (remainingSeconds == null) remainingSeconds = runtime.getRemainingSeconds();
        if (progressPercent == null) progressPercent = runtime.getProgressPercent();
        if (cycleActive == null) cycleActive = runtime.getCycleActive();
        if (phase == null) phase = runtime.getPhase();
        if (motorDirection == null) motorDirection = runtime.getMotorDirection();
        if (doorClosed == null) doorClosed = runtime.getDoorClosed();
        if (leakDetected == null) leakDetected = runtime.getLeakDetected();
        if (wifiRssi == null) wifiRssi = runtime.getWifiRssi();
        if (uptimeSeconds == null) uptimeSeconds = runtime.getUptimeSeconds();
    }
}