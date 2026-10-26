package com.laundry.iot.dto;

import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.machine.entity.MachineDeploymentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response bodies of the device provisioning + commissioning API. */
public final class ProvisioningDtos {

    private ProvisioningDtos() {}

    public static final String DEVICE_ID_REGEX = "^ESP-[0-9A-F]{12}$";

    // ── Stage 1: enrollment (admin, workshop) ──────────────────────────────

    public record EnrollDeviceRequest(
            @NotBlank @Pattern(regexp = DEVICE_ID_REGEX, message = "deviceId must look like ESP-24A1B2C3D4E5")
            String deviceId) {}

    /**
     * Returned once. {@code claimSecret} is written to the ESP32 over USB
     * ({@code claim <hex>}); {@code qrPayload} goes on the physical label.
     */
    public record EnrollDeviceResponse(String deviceId, String claimSecret,
                                       String pairingCode, String qrPayload) {}

    public record DeviceSummary(String deviceId, IotDeviceStatus status, UUID machineId, String machineCode,
                                String firmwareVersion, OffsetDateTime enrolledAt,
                                OffsetDateTime activatedAt, OffsetDateTime commissionedAt,
                                OffsetDateTime revokedAt) {}

    /** What the admin console needs to adapt its UI to the backend configuration. */
    public record ProvisioningSettings(String emqxProvisioningMode, int pairingTtlSeconds,
                                       int selfTestButtonTimeoutSeconds) {}

    // ── Stage 3: pairing (admin scans QR at the machine) ──────────────────

    public record StartPairingRequest(
            @NotBlank @Pattern(regexp = DEVICE_ID_REGEX) String deviceId,
            @NotBlank String pairingCode) {}

    public record PairingResponse(UUID sessionId, String machineCode, String deviceId,
                                  OffsetDateTime expiresAt) {}

    // ── Stage 5–6: activation (ESP32 → backend over HTTPS) ────────────────

    public record ChallengeRequest(@NotBlank @Pattern(regexp = DEVICE_ID_REGEX) String deviceId) {}

    public record ChallengeResponse(String nonce, int expiresInSeconds) {}

    public record ActivateRequest(
            @NotBlank @Pattern(regexp = DEVICE_ID_REGEX) String deviceId,
            @NotBlank String nonce,
            /** hex(HMAC-SHA256(claimSecret, deviceId + ":" + nonce)) */
            @NotBlank String proof,
            String firmwareVersion) {}

    /** {@code machineType} lets a board refuse a record of the wrong kind (a locker paired to a dryer). */
    public record ActivateResponse(String mqttHost, int mqttPort, String username, String password,
                                   String clientId, UUID branchId, String machineCode, String topicBase,
                                   String machineType) {}

    /** MANUAL mode only: what the admin must enter in the EMQX console. */
    public record MqttCredentialResponse(String username, String password, String clientId,
                                         List<String> publishTopics, List<String> subscribeTopics) {}

    // ── Stage 7–8: self-test + commissioning ──────────────────────────────

    public record ProvisioningStatus(String machineCode, MachineDeploymentStatus deploymentStatus,
                                     String deviceId, IotDeviceStatus deviceStatus, boolean online,
                                     OffsetDateTime lastHeartbeatAt, String firmwareVersion,
                                     OffsetDateTime pairingExpiresAt, OffsetDateTime lastSelfTestAt,
                                     Boolean lastSelfTestPassed, String lastSelfTestDetail) {}

    public record SelfTestResponse(String commandId, int buttonTimeoutSeconds) {}
}
