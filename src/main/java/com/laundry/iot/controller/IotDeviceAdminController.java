package com.laundry.iot.controller;

import com.laundry.iot.config.EmqxAdminProperties;
import com.laundry.iot.config.IotProvisioningProperties;
import com.laundry.iot.dto.ProvisioningDtos.*;
import com.laundry.iot.service.DeviceProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Stage 1 (workshop enrollment) and device revocation. Admin/owner only
 * (enforced in {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/admin/iot-devices")
@RequiredArgsConstructor
@Tag(name = "IoT Devices (Admin)", description = "ESP32 enrollment, credentials and revocation")
public class IotDeviceAdminController {

    private final DeviceProvisioningService provisioningService;
    private final EmqxAdminProperties emqxProperties;
    private final IotProvisioningProperties iotProperties;

    @PostMapping
    @Operation(summary = "Enroll an ESP32; returns its claim secret and pairing code once")
    public ResponseEntity<EnrollDeviceResponse> enroll(@Valid @RequestBody EnrollDeviceRequest request) {
        return ResponseEntity.status(201).body(provisioningService.enroll(request.deviceId()));
    }

    @GetMapping
    @Operation(summary = "List enrolled devices")
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
}
