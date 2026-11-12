package com.laundry.iot.controller;

import com.laundry.iot.dto.ProvisioningDtos.*;
import com.laundry.iot.service.CommissioningService;
import com.laundry.iot.service.DeviceRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * One machine's commissioning: attach an approved board, run the remote
 * self-test, commission, and take it into maintenance. Lives under {@code /api/admin/machines} so the
 * existing admin/owner rule in {@code SecurityConfig} applies.
 */
@RestController
@RequestMapping("/api/admin/machines/{machineCode}")
@RequiredArgsConstructor
@Tag(name = "Machine Provisioning (Admin)", description = "Pairing, self-test and commissioning")
public class MachineProvisioningController {

    private final DeviceRegistryService registryService;
    private final CommissioningService commissioningService;

    @GetMapping("/provisioning")
    @Operation(summary = "Deployment status, bound device, heartbeat and last self-test")
    public ProvisioningStatus status(@PathVariable String machineCode) {
        return commissioningService.status(machineCode);
    }

    @PostMapping("/pairing")
    @Operation(summary = "Attach an approved (VERIFIED) board; its first heartbeat starts the self-test")
    public ProvisioningStatus pair(@PathVariable String machineCode,
                                   @Valid @RequestBody PairDeviceRequest request,
                                   Authentication authentication) {
        registryService.startPairing(machineCode, request, userId(authentication));
        return commissioningService.status(machineCode);
    }

    @PostMapping("/self-test")
    @Operation(summary = "Send SELF_TEST over USB; the result arrives on the device event channel")
    public SelfTestResponse selfTest(@PathVariable String machineCode, Authentication authentication) {
        return commissioningService.startSelfTest(machineCode, userId(authentication));
    }

    @PostMapping("/commission")
    @Operation(summary = "Mark the machine ACTIVE (bookable) after a passing self-test")
    public ProvisioningStatus commission(@PathVariable String machineCode, Authentication authentication) {
        return commissioningService.commission(machineCode, userId(authentication));
    }

    @PostMapping("/maintenance")
    @Operation(summary = "Take an ACTIVE machine out of booking for a hardware repair;"
            + " it returns via /self-test then /commission")
    public ProvisioningStatus startMaintenance(@PathVariable String machineCode,
                                               @Valid @RequestBody(required = false) StartMaintenanceRequest request,
                                               Authentication authentication) {
        return commissioningService.startMaintenance(machineCode, userId(authentication),
                request == null ? null : request.reason());
    }

    @PostMapping("/unpair")
    @Operation(summary = "Detach and revoke the device; machine returns to UNASSIGNED")
    public ResponseEntity<Void> unpair(@PathVariable String machineCode) {
        registryService.unpair(machineCode);
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
