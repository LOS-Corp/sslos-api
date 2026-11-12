package com.laundry.iot.controller;

import com.laundry.iot.config.IotProvisioningProperties;
import com.laundry.iot.dto.ProvisioningDtos.DeviceSummary;
import com.laundry.iot.dto.ProvisioningDtos.ProvisioningSettings;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.firmware.FirmwareUpdateService;
import com.laundry.iot.firmware.FirmwareUpdateStatus;
import com.laundry.iot.service.DeviceRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Boards seen on the store server's USB ports: approve a new board, revoke
 * one, or flash new firmware onto it. Admin only (enforced in {@code SecurityConfig}). A device row is created
 * the first time the board says HELLO over USB.
 */
@RestController
@RequestMapping("/api/admin/iot-devices")
@RequiredArgsConstructor
@Tag(name = "IoT Devices (Admin)", description = "Approve and revoke USB-connected boards")
public class IotDeviceAdminController {

    private final DeviceRegistryService registryService;
    private final IotProvisioningProperties iotProperties;
    private final FirmwareUpdateService firmwareService;

    @GetMapping
    @Operation(summary = "Boards that have said HELLO at least once, with their USB connection")
    public List<DeviceSummary> list() {
        return registryService.listDevices();
    }

    @GetMapping("/settings")
    @Operation(summary = "Device link type and self-test timeout")
    public ProvisioningSettings settings() {
        return new ProvisioningSettings("USB_SERIAL", iotProperties.getSelfTestButtonTimeoutSeconds());
    }

    @PostMapping("/{deviceId}/approve")
    @Operation(summary = "Approve a board (UNCLAIMED or REVOKED → VERIFIED) so it can be attached to a machine")
    public DeviceSummary approve(@PathVariable String deviceId, Authentication authentication) {
        return registryService.approve(deviceId, userId(authentication));
    }

    @PostMapping("/{deviceId}/revoke")
    @Operation(summary = "Revoke or reject a board; unpairs its machine first")
    public ResponseEntity<Void> revoke(@PathVariable String deviceId) {
        registryService.revoke(deviceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{deviceId}/firmware", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Flash firmware.bin onto a plugged-in board: the backend releases its COM port, "
            + "runs esptool and takes the port back. Returns at once; poll GET for progress")
    public ResponseEntity<FirmwareUpdateStatus> flash(@PathVariable String deviceId,
                                                      @RequestParam("file") MultipartFile file,
                                                      Authentication authentication) throws IOException {
        FirmwareUpdateStatus status = firmwareService.start(deviceId, file.getBytes(), userId(authentication));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status);
    }

    @GetMapping("/{deviceId}/firmware")
    @Operation(summary = "Progress of the board's last firmware update since the backend started")
    public FirmwareUpdateStatus firmwareStatus(@PathVariable String deviceId) {
        return firmwareService.status(deviceId).orElseThrow(() ->
                new ProvisioningRejectedException(HttpStatus.NOT_FOUND, "No firmware update for " + deviceId));
    }

    private static UUID userId(Authentication authentication) {
        try {
            return authentication == null ? null : UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
