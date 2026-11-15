package com.laundry.iot.dto;

import com.laundry.devicelink.DeviceIds;
import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.machine.entity.MachineDeploymentStatus;
import com.laundry.machine.entity.MachineType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Request/response bodies of the device approval, pairing and commissioning API. */
public final class ProvisioningDtos {

    private ProvisioningDtos() {}

    public static final String DEVICE_ID_REGEX = DeviceIds.REGEX;

    /**
     * A board that has said HELLO on the store broker at least once.
     *
     * @param connected             on the broker right now, with an accepted HELLO
     * @param ipAddress             address on the store Wi-Fi from its last HELLO
     * @param wifiRssi              Wi-Fi signal (dBm) from its last HELLO
     * @param keyFingerprint        8 digits of the pinned key, e.g. {@code A1B2-C3D4}: must match the board's screen
     * @param pendingKeyFingerprint a different key the board was refused with; an admin may trust it
     * @param authFailures          refused connections since the key was last trusted
     */
    public record DeviceSummary(String deviceId, IotDeviceStatus status, MachineType deviceType,
                                UUID machineId, String machineCode, String firmwareVersion,
                                boolean connected,
                                OffsetDateTime enrolledAt, OffsetDateTime lastSeenAt,
                                OffsetDateTime verifiedAt, OffsetDateTime activatedAt,
                                OffsetDateTime commissionedAt, OffsetDateTime revokedAt,
                                String ipAddress, Integer wifiRssi,
                                String keyFingerprint, String pendingKeyFingerprint,
                                int authFailures, OffsetDateTime lastAuthFailureAt) {}

    /**
     * An approved board not attached to any machine yet: what the owner picks when
     * creating a machine or on its "Lắp đặt" tab.
     */
    public record AvailableDevice(String deviceId, MachineType deviceType, String firmwareVersion,
                                  boolean connected, OffsetDateTime verifiedAt) {}

    /**
     * What the admin console needs to adapt its UI to the backend configuration.
     *
     * @param wifi what to type into a board's setup page ({@code null} when the MQTT link is off)
     */
    public record ProvisioningSettings(int selfTestButtonTimeoutSeconds, WifiSetup wifi) {}

    /**
     * What the technician types into a board's setup page ({@code LaundryOS-Setup-XXXX}, 192.168.4.1): the store
     * server's LAN address. The board registers on {@code apiPort} and gets its own MQTT account for {@code mqttPort}.
     *
     * @param serverHost      LAN address of the store server ({@code MQTT_DEVICE_HOST}); empty when not set
     * @param brokerConnected the backend's own MQTT session is up
     */
    public record WifiSetup(String serverHost, int apiPort, int mqttPort, boolean brokerConnected) {}

    // ── Board registration (POST /api/devices/register) ───────────────────

    public record RegisterChallengeRequest(
            @NotBlank @Pattern(regexp = DEVICE_ID_REGEX, message = "deviceId must look like ESP-24A1B2C3D4E5")
            String deviceId) {}

    /** Single-use, valid for a minute: the board signs {@code LAUNDRYOS-REGISTER|<deviceId>|<nonce>}. */
    public record RegisterChallenge(String nonce, int expiresInSeconds) {}

    /**
     * @param publicKey base64 of the board's 65-byte P-256 public key
     * @param signature base64 DER ECDSA signature of {@code LAUNDRYOS-REGISTER|<deviceId>|<nonce>}
     */
    public record RegisterRequest(
            @NotBlank @Pattern(regexp = DEVICE_ID_REGEX) String deviceId,
            @Size(max = 32) String firmwareVersion,
            @Size(max = 16) String deviceType,
            @NotBlank @Size(max = 200) String publicKey,
            @NotBlank @Size(max = 100) String nonce,
            @NotBlank @Size(max = 200) String signature,
            Integer rssi) {}

    /**
     * The board's own broker account: username = deviceId, the password sealed to the board's key
     * ({@code epk}, {@code iv}, {@code ct}: ECDH P-256 + AES-256-GCM, see {@code DeviceKeys.seal}).
     * {@code state} is the board's link state (PENDING until an admin approves it).
     */
    public record RegisterResponse(String state, String username, int mqttPort, String epk, String iv, String ct) {}

    /** Why a registration was refused: {@code AUTH_FAILED} (another key is pinned) or {@code REVOKED}. */
    public record RegisterRefused(String state, String message) {}

    /**
     * Attach an approved ({@code VERIFIED}) board to a machine. Also nested in
     * {@code CreateMachineRequest.device}, so a machine can be created and paired in one step.
     */
    public record PairDeviceRequest(
            @NotBlank @Pattern(regexp = DEVICE_ID_REGEX, message = "deviceId must look like ESP-24A1B2C3D4E5")
            String deviceId) {}

    // ── Self-test + commissioning ─────────────────────────────────────────

    public record ProvisioningStatus(String machineCode, MachineDeploymentStatus deploymentStatus,
                                     String deviceId, IotDeviceStatus deviceStatus,
                                     boolean deviceConnected, boolean online,
                                     OffsetDateTime lastHeartbeatAt, String firmwareVersion,
                                     OffsetDateTime lastSelfTestAt,
                                     Boolean lastSelfTestPassed, String lastSelfTestDetail,
                                     OffsetDateTime maintenanceSince) {}

    public record StartMaintenanceRequest(@Size(max = 500) String reason) {}

    public record SelfTestResponse(String commandId, int buttonTimeoutSeconds) {}
}
