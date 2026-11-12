package com.laundry.iot.dto;

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

    public static final String DEVICE_ID_REGEX = "^ESP-[0-9A-F]{12}$";

    /**
     * A board the store server has seen on USB.
     *
     * @param connected plugged in right now (has said HELLO on {@code port})
     */
    public record DeviceSummary(String deviceId, IotDeviceStatus status, MachineType deviceType,
                                UUID machineId, String machineCode, String firmwareVersion,
                                boolean connected, String port,
                                OffsetDateTime enrolledAt, OffsetDateTime lastSeenAt,
                                OffsetDateTime verifiedAt, OffsetDateTime activatedAt,
                                OffsetDateTime commissionedAt, OffsetDateTime revokedAt) {}

    /**
     * An approved board not attached to any machine yet: what the owner picks when
     * creating a machine or on its "Lắp đặt" tab.
     */
    public record AvailableDevice(String deviceId, MachineType deviceType, String firmwareVersion,
                                  boolean connected, String port, OffsetDateTime verifiedAt) {}

    /** What the admin console needs to adapt its UI to the backend configuration. */
    public record ProvisioningSettings(String deviceLink, int selfTestButtonTimeoutSeconds) {}

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
                                     boolean deviceConnected, String port, boolean online,
                                     OffsetDateTime lastHeartbeatAt, String firmwareVersion,
                                     OffsetDateTime lastSelfTestAt,
                                     Boolean lastSelfTestPassed, String lastSelfTestDetail,
                                     OffsetDateTime maintenanceSince) {}

    public record StartMaintenanceRequest(@Size(max = 500) String reason) {}

    public record SelfTestResponse(String commandId, int buttonTimeoutSeconds) {}
}
