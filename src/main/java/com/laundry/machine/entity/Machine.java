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
 * is NOT persisted into PostgreSQL. It is streamed directly via MQTT → Spring Boot → WebSocket/STOMP.
 *
 * <p>MQTT identity is scoped by {@code (branchId-UUID, machineCode)} — every device
 * publishes to a topic that includes the {@link UUID} of the branch it is installed in.
 */
@Entity
@Table(
    name = "machines",
    indexes = {
        @Index(name = "idx_machine_branch_code", columnList = "branch_id, machine_code", unique = true),
        @Index(name = "idx_machine_code", columnList = "machine_code"),
        @Index(name = "idx_machine_status", columnList = "status"),
        @Index(name = "idx_machine_heartbeat", columnList = "last_heartbeat_at"),
        @Index(name = "idx_machine_branch", columnList = "branch_id")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_machines_branch_machine", columnNames = {"branch_id", "machine_code"})
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
     * Machine identifier matching the MQTT topic segment (e.g., "WM001").
     * Unique within each branch.
     */
    @Column(name = "machine_code", nullable = false, length = 50)
    private String machineCode;

    /**
     * Branch/store this machine belongs to. Required — MQTT topics are scoped per branch.
     */
    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MachineType type = MachineType.WASHER;

    /** Display name shown in the management dashboard. */
    @Column(name = "display_name", length = 100)
    private String displayName;

    /** Physical location or description within the branch. */
    @Column(name = "location_description", length = 200)
    private String locationDescription;

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