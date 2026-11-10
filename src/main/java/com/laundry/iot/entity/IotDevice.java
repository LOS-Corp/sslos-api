package com.laundry.iot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A physical ESP32 controller and its provisioning state.
 *
 * <p>No secret is stored here in plain text: the MQTT password is derived on
 * demand from the server-side device key (see {@code DeviceSecretService}),
 * and the setup key scanned from the device screen is kept AES-GCM encrypted
 * only while a pairing session is open.
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

    /** Bumped on every credential issuance so the MQTT password rotates. */
    @Column(name = "credential_version", nullable = false)
    @Builder.Default
    private int credentialVersion = 0;

    /** Setup key from the scanned device QR, encrypted; wiped once the device activates. */
    @Column(name = "setup_key_enc", columnDefinition = "TEXT")
    private String setupKeyEnc;

    /** Wrong activation proofs in the current pairing; the key is wiped at the limit. */
    @Column(name = "failed_pairing_attempts", nullable = false)
    @Builder.Default
    private int failedPairingAttempts = 0;

    @Column(name = "machine_id")
    private UUID machineId;

    @Column(name = "mqtt_username", length = 64)
    private String mqttUsername;

    @Column(name = "challenge_nonce", length = 64)
    private String challengeNonce;

    @Column(name = "challenge_expires_at")
    private OffsetDateTime challengeExpiresAt;

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

    /** First time an admin scanned this board. */
    @Column(name = "enrolled_at", nullable = false)
    private OffsetDateTime enrolledAt;

    /** Board proved it holds the scanned code (device added before its machine). */
    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

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
