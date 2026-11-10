package com.laundry.iot.dto;

import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.machine.entity.MachineDeploymentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request/response bodies of the device provisioning + commissioning API. */
public final class ProvisioningDtos {

    private ProvisioningDtos() {}

    public static final String DEVICE_ID_REGEX = "^ESP-[0-9A-F]{12}$";

    /** 6-digit pairing code shown on the board (QR + digits). A space or dash between the groups is allowed. */
    public static final String SETUP_KEY_REGEX = "^[0-9 -]{6,9}$";

    public record DeviceSummary(String deviceId, IotDeviceStatus status, UUID machineId, String machineCode,
                                String firmwareVersion, OffsetDateTime enrolledAt,
                                OffsetDateTime activatedAt, OffsetDateTime commissionedAt,
                                OffsetDateTime revokedAt, OffsetDateTime verifiedAt) {}

    /** What the admin console needs to adapt its UI to the backend configuration. */
    public record ProvisioningSettings(String emqxProvisioningMode, int pairingTtlSeconds,
                                       int selfTestButtonTimeoutSeconds) {}

    // ── Pairing (admin scans the setup QR on the board's screen) ──────────

    /**
     * Content of the setup QR: {@code LOS:<deviceId>:<setupKey>}. Also nested in
     * {@code CreateMachineRequest.device} so a machine can be created and paired
     * in one step. {@code setupKey} may be omitted only for a VERIFIED device
     * (added earlier on the IoT page), whose code the backend already holds.
     */
    public record StartPairingRequest(
            @NotBlank @Pattern(regexp = DEVICE_ID_REGEX, message = "deviceId must look like ESP-24A1B2C3D4E5")
            String deviceId,
            @Pattern(regexp = SETUP_KEY_REGEX, message = "setupKey must be the 6-digit code on the device screen")
            String setupKey) {}

    /** Device-first: the board's code was scanned on the IoT page; verification is open until {@code expiresAt}. */
    public record RegisterDeviceResponse(UUID sessionId, String deviceId, OffsetDateTime expiresAt) {}

    public record PairingResponse(UUID sessionId, String machineCode, String deviceId,
                                  OffsetDateTime expiresAt) {}

    // ── Activation (ESP32 → backend; the board polls while unprovisioned) ─

    public record ChallengeRequest(@NotBlank @Pattern(regexp = DEVICE_ID_REGEX) String deviceId) {}

    public record ChallengeResponse(String nonce, int expiresInSeconds) {}

    public record ActivateRequest(
            @NotBlank @Pattern(regexp = DEVICE_ID_REGEX) String deviceId,
            @NotBlank String nonce,
            /** hex(HMAC-SHA256(setupKey, deviceId + ":" + nonce)) */
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
                                     Boolean lastSelfTestPassed, String lastSelfTestDetail,
                                     OffsetDateTime maintenanceSince) {}

    public record StartMaintenanceRequest(@Size(max = 500) String reason) {}

    public record SelfTestResponse(String commandId, int buttonTimeoutSeconds) {}
}
