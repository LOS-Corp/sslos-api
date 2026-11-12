package com.laundry.iot.controller;

import com.laundry.iot.dto.ProvisioningDtos.AvailableDevice;
import com.laundry.iot.service.DeviceRegistryService;
import com.laundry.machine.entity.MachineType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Approved boards waiting for a machine. Under {@code /api/admin/machines} so the
 * owner (who attaches boards to machines) can read it, not only the admin who
 * approves them.
 */
@RestController
@RequestMapping("/api/admin/machines/available-devices")
@RequiredArgsConstructor
@Tag(name = "Machine Provisioning (Admin)", description = "Pairing, self-test and commissioning")
public class AvailableDeviceController {

    private final DeviceRegistryService registryService;

    @GetMapping
    @Operation(summary = "Approved (VERIFIED) boards not attached to a machine; filter by firmware type")
    public List<AvailableDevice> list(@RequestParam(required = false) MachineType type) {
        return registryService.availableDevices(type);
    }
}
