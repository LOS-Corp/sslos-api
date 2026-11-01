package com.laundry.iot.controller;

import com.laundry.iot.dto.ProvisioningDtos.*;
import com.laundry.iot.service.CommissioningService;
import com.laundry.iot.service.DeviceProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Stages 3, 7 and 8 for one machine: pair a scanned device, run the remote
 * self-test, commission. Lives under {@code /api/admin/machines} so the
 * existing admin/owner rule in {@code SecurityConfig} applies.
 */
@RestController
@RequestMapping("/api/admin/machines/{machineCode}")
@RequiredArgsConstructor
@Tag(name = "Machine Provisioning (Admin)", description = "Pairing, self-test and commissioning")
public class MachineProvisioningController {

    private final DeviceProvisioningService provisioningService;
    private final CommissioningService commissioningService;

    @GetMapping("/provisioning")
    @Operation(summary = "Deployment status, bound device, heartbeat and last self-test")
    public ProvisioningStatus status(@PathVariable String machineCode) {
        return commissioningService.status(machineCode);
    }

    @PostMapping("/pairing")
    @Operation(summary = "Open a pairing session with the device whose QR was scanned")
    public PairingResponse pair(@PathVariable String machineCode,
                                @Valid @RequestBody StartPairingRequest request,
                                Authentication authentication) {
        return provisioningService.startPairing(machineCode, request, userId(authentication));
    }

    @PostMapping("/self-test")
    @Operation(summary = "Send SELF_TEST over MQTT; result arrives on the device /event channel")
    public SelfTestResponse selfTest(@PathVariable String machineCode, Authentication authentication) {
        return commissioningService.startSelfTest(machineCode, userId(authentication));
    }

    @PostMapping("/commission")
    @Operation(summary = "Mark the machine ACTIVE (bookable) after a passing self-test")
    public ProvisioningStatus commission(@PathVariable String machineCode, Authentication authentication) {
        return commissioningService.commission(machineCode, userId(authentication));
    }

    @PostMapping("/unpair")
    @Operation(summary = "Detach and revoke the device; machine returns to UNASSIGNED")
    public ResponseEntity<Void> unpair(@PathVariable String machineCode) {
        provisioningService.unpair(machineCode);
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
