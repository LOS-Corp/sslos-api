package com.laundry.iot.entity;

import com.laundry.machine.entity.MachineType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A physical board and its approval/binding state. The board talks to the
 * backend over USB serial, so it holds no credential: being plugged into the
 * store server plus an admin's approval is what lets it in.
 */
@Entity
@Table(name = "iot_devices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IotDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Hardware identity derived from the eFuse MAC, e.g. "ESP-24A1B2C3D4E5". */
    @Column(name = "device_id", nullable = false, unique = true, length = 64)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IotDeviceStatus status;

    /** Kind of machine the firmware drives, as reported in HELLO; pairing must match it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "device_type", length = 20)
    private MachineType deviceType;

    @Column(name = "machine_id")
    private UUID machineId;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    @Column(name = "last_self_test_command_id", length = 80)
    private String lastSelfTestCommandId;

    @Column(name = "last_self_test_at")
    private OffsetDateTime lastSelfTestAt;

    @Column(name = "last_self_test_passed")
    private Boolean lastSelfTestPassed;

    @Column(name = "last_self_test_detail", columnDefinition = "TEXT")
    private String lastSelfTestDetail;

    /** First HELLO from this board. */
    @Column(name = "enrolled_at", nullable = false)
    private OffsetDateTime enrolledAt;

    /** Last HELLO (plug-in or board restart). */
    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;

    /** When and by whom the board was approved. */
    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    @Column(name = "verified_by")
    private UUID verifiedBy;

    /** Attached to its machine. */
    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "commissioned_at")
    private OffsetDateTime commissionedAt;

    @Column(name = "commissioned_by")
    private UUID commissionedBy;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
        if (enrolledAt == null) enrolledAt = updatedAt;
    }
}
