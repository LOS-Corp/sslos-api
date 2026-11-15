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
 * A physical board and its approval/binding state. On every connection it proves itself with the
 * ECDSA key pinned in {@link #publicKey} (see {@code DeviceKeys}); an admin's approval lets it in.
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

    // ── Wi-Fi identity (V59) ────────────────────────────────────────────────

    /** Pinned P-256 public key (base64 of the 65-byte point); null until the board first joined over Wi-Fi. */
    @Column(name = "public_key", columnDefinition = "TEXT")
    private String publicKey;

    /** SHA-256 of {@link #publicKey}, 64 hex digits; the board's screen shows the first 8. Unique. */
    @Column(name = "key_fingerprint", length = 64)
    private String keyFingerprint;

    /** A different key the board presented and was refused with; an admin may trust it. */
    @Column(name = "pending_public_key", columnDefinition = "TEXT")
    private String pendingPublicKey;

    @Column(name = "pending_key_fingerprint", length = 64)
    private String pendingKeyFingerprint;

    @Column(name = "pending_key_seen_at")
    private OffsetDateTime pendingKeySeenAt;

    /** Refused connections since the key was last trusted. */
    @Builder.Default
    @Column(name = "auth_failures", nullable = false)
    private int authFailures = 0;

    @Column(name = "last_auth_failure_at")
    private OffsetDateTime lastAuthFailureAt;

    /** The board's address on the store Wi-Fi, from its last HELLO. */
    @Column(name = "last_ip", length = 45)
    private String lastIp;

    /** Wi-Fi signal strength (dBm) from its last HELLO. */
    @Column(name = "last_rssi")
    private Integer lastRssi;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
        if (enrolledAt == null) enrolledAt = updatedAt;
    }
}
