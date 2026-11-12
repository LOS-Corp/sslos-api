package com.laundry.machine.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Represents a physical or simulated laundry machine (washer, dryer, etc.).
 *
 * <p>Architecture:
 * <pre>
 * Machine
 * ├── Static Configuration / Registry
 * └── Minimal Last Known State
 * </pre>
 *
 * <p>High-frequency sensor telemetry (weight, water level, vibration, remaining seconds, etc.)
 * is NOT persisted into PostgreSQL. It is streamed directly via USB serial → Spring Boot → WebSocket/STOMP.
 *
 * <p>Device traffic is keyed by {@code machineCode}, unique in the store (V58).
 */
@Entity
@Table(
    name = "machines",
    indexes = {
        @Index(name = "idx_machine_code", columnList = "machine_code"),
        @Index(name = "idx_machine_status", columnList = "status"),
        @Index(name = "idx_machine_heartbeat", columnList = "last_heartbeat_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_machines_machine_code", columnNames = {"machine_code"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Machine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    // ── Static Configuration / Registry ─────────────────────────────────────

    /**
     * Machine identifier used in device frames (e.g., "WM001").
     * Unique in the store.
     */
    @Column(name = "machine_code", nullable = false, length = 50)
    private String machineCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MachineType type = MachineType.WASHER;

    /** Display name shown in the management dashboard. */
    @Column(name = "display_name", length = 100)
    private String displayName;

    /** Physical location or description within the store. */
    @Column(name = "location_description", length = 200)
    private String locationDescription;

    /** Rated load in kilograms, shown to customers when choosing a machine; null when unknown. */
    @Column(name = "capacity_kg", precision = 5, scale = 1)
    private java.math.BigDecimal capacityKg;

    /**
     * Commissioning lifecycle. Only {@link MachineDeploymentStatus#ACTIVE}
     * machines are offered for booking and check-in.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_status", nullable = false, length = 20)
    @Builder.Default
    private MachineDeploymentStatus deploymentStatus = MachineDeploymentStatus.UNASSIGNED;

    /**
     * When the current hardware repair began; null outside maintenance. Stays
     * set through the post-repair self-test so a failure returns the machine
     * to {@link MachineDeploymentStatus#MAINTENANCE}; cleared on commissioning.
     */
    @Column(name = "maintenance_since")
    private OffsetDateTime maintenanceSince;

    /** ESP32 identity (e.g. "ESP-24A1B2C3D4E5") bound to this machine; null when unassigned. */
    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // ── Minimal Last Known State ─────────────────────────────────────────────

    /**
     * Current business operational status.
     * Transitions are validated in MachineStateService.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private MachineStatus status = MachineStatus.AVAILABLE;

    /**
     * Connectivity state:
     * true = backend receives valid heartbeats/messages from device.
     * false = device disconnected / heartbeat timeout.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean online = false;

    /** Timestamp of the last received valid message/heartbeat from the device. */
    @Column(name = "last_heartbeat_at")
    private OffsetDateTime lastHeartbeatAt;

    /** Error code string from the device; empty when no error. */
    @Column(name = "error_code", length = 100)
    @Builder.Default
    private String errorCode = "";

    /** True when the machine finished commissioning and may be sold to customers. */
    public boolean isCommissioned() {
        return deploymentStatus == MachineDeploymentStatus.ACTIVE;
    }

    /** True from the start of a repair until the machine is commissioned again. */
    public boolean isUnderMaintenance() {
        return maintenanceSince != null;
    }

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}