package com.laundry.iot.controller;

import com.laundry.devicelink.mqtt.DeviceTrafficLog;
import com.laundry.iot.config.IotProvisioningProperties;
import com.laundry.iot.dto.ProvisioningDtos.DeviceSummary;
import com.laundry.iot.dto.ProvisioningDtos.ProvisioningSettings;
import com.laundry.iot.service.DeviceNetworkService;
import com.laundry.iot.service.DeviceRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Boards seen on the store broker: approve a new board, revoke one, trust a board's new key, send a board
 * back to Wi-Fi setup mode, and watch the MQTT traffic. Admin only (enforced in {@code SecurityConfig}).
 * A device row is created the first time the board says HELLO.
 */
@RestController
@RequestMapping("/api/admin/iot-devices")
@RequiredArgsConstructor
@Tag(name = "IoT Devices (Admin)", description = "Approve, revoke and watch the boards on the store broker")
public class IotDeviceAdminController {

    private final DeviceRegistryService registryService;
    private final IotProvisioningProperties iotProperties;
    private final DeviceNetworkService networkService;
    private final DeviceTrafficLog trafficLog;

    @GetMapping
    @Operation(summary = "Boards that have said HELLO at least once, and whether they are connected now")
    public List<DeviceSummary> list() {
        return registryService.listDevices();
    }

    @GetMapping("/settings")
    @Operation(summary = "Self-test timeout, and the broker settings for a board's Wi-Fi setup page")
    public ProvisioningSettings settings() {
        return new ProvisioningSettings(iotProperties.getSelfTestButtonTimeoutSeconds(), networkService.wifiSetup());
    }

    @GetMapping("/traffic")
    @Operation(summary = "MQTT messages to and from the boards newer than the cursor 'after' (0 = everything kept in memory). "
            + "Polled with a cursor instead of streamed, so it works with the normal bearer-token requests")
    public DeviceTrafficLog.Snapshot traffic(@RequestParam(defaultValue = "0") long after) {
        return trafficLog.read(Math.max(0, after));
    }

    @PostMapping("/{deviceId}/trust-key")
    @Operation(summary = "Trust the key a board was refused with (compare its fingerprint with the board's screen first)")
    public DeviceSummary trustKey(@PathVariable String deviceId, Authentication authentication) {
        return registryService.trustPendingKey(deviceId, userId(authentication));
    }

    @PostMapping("/{deviceId}/network-reset")
    @Operation(summary = "Send a board back to Wi-Fi setup mode (SoftAP LaundryOS-Setup-XXXX); refused while its machine is in use")
    public ResponseEntity<Void> resetNetwork(@PathVariable String deviceId, Authentication authentication) {
        networkService.resetWifi(deviceId, userId(authentication));
        return ResponseEntity.accepted().build();
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

    private static UUID userId(Authentication authentication) {
        try {
            return authentication == null ? null : UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
