package com.laundry.iot.controller;

import com.laundry.iot.config.EmqxAdminProperties;
import com.laundry.iot.config.IotProvisioningProperties;
import com.laundry.iot.dto.ProvisioningDtos.*;
import com.laundry.iot.service.DeviceProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Device catalog, adding a board before its machine, MANUAL-mode EMQX
 * credentials and revocation. Admin only (enforced in {@code SecurityConfig}).
 * A device row is created the first time its code is scanned, here or while
 * pairing a machine.
 */
@RestController
@RequestMapping("/api/admin/iot-devices")
@RequiredArgsConstructor
@Tag(name = "IoT Devices (Admin)", description = "ESP32 catalog, credentials and revocation")
public class IotDeviceAdminController {

    private final DeviceProvisioningService provisioningService;
    private final EmqxAdminProperties emqxProperties;
    private final IotProvisioningProperties iotProperties;

    @PostMapping
    @Operation(summary = "Add a board before its machine: open a verification with the code shown on the board")
    public ResponseEntity<RegisterDeviceResponse> register(@Valid @RequestBody StartPairingRequest request,
                                                           Authentication authentication) {
        return ResponseEntity.status(201).body(provisioningService.registerDevice(request, userId(authentication)));
    }

    @GetMapping
    @Operation(summary = "List devices that have been scanned at least once")
    public List<DeviceSummary> list() {
        return provisioningService.listDevices();
    }

    @GetMapping("/settings")
    @Operation(summary = "EMQX provisioning mode and pairing/self-test timeouts")
    public ProvisioningSettings settings() {
        return new ProvisioningSettings(emqxProperties.getProvisioningMode().name(),
                iotProperties.getPairingTtlSeconds(), iotProperties.getSelfTestButtonTimeoutSeconds());
    }

    @GetMapping("/{deviceId}/mqtt-credential")
    @Operation(summary = "MANUAL EMQX mode: credential + ACL to enter in the EMQX console")
    public MqttCredentialResponse mqttCredential(@PathVariable String deviceId) {
        return provisioningService.mqttCredential(deviceId);
    }

    @PostMapping("/{deviceId}/revoke")
    @Operation(summary = "Revoke a device: delete its EMQX identity and unpair its machine")
    public ResponseEntity<Void> revoke(@PathVariable String deviceId) {
        provisioningService.revoke(deviceId);
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Authentication authentication) {
        try {
            return authentication == null ? null : UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
