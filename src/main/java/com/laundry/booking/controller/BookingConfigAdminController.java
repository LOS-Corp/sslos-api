package com.laundry.booking.controller;

import com.laundry.booking.dto.BookingConfigDtos.BookingSettingsDto;
import com.laundry.booking.dto.BookingConfigDtos.BookingSettingsUpdate;
import com.laundry.booking.dto.BookingConfigDtos.BranchReadiness;
import com.laundry.booking.dto.BookingConfigDtos.ResourceUpdate;
import com.laundry.booking.dto.BookingConfigDtos.ServiceOfferingDto;
import com.laundry.booking.dto.BookingDtos.BookingHoursRequest;
import com.laundry.booking.dto.BookingDtos.BranchHoursDto;
import com.laundry.booking.dto.BookingDtos.BranchOfferingRequest;
import com.laundry.booking.dto.BookingDtos.CreateResourceRequest;
import com.laundry.booking.dto.BookingDtos.ResourceDto;
import com.laundry.booking.service.BranchBookingReadinessService;
import com.laundry.booking.service.BranchBookingConfigService;
import com.laundry.machine.dto.MachineDto;
import com.laundry.machine.service.MachineCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/booking-config/branches")
@RequiredArgsConstructor
public class BookingConfigAdminController {
    private final BranchBookingReadinessService readinessService;
    private final BranchBookingConfigService configService;
    private final MachineCatalogService machineCatalogService;

    @GetMapping
    public List<BranchReadiness> overview() {
        return readinessService.evaluateAll();
    }

    @GetMapping("/{branchId}")
    public BranchReadiness configuration(@PathVariable UUID branchId) {
        return readinessService.evaluate(branchId);
    }

    @GetMapping("/{branchId}/readiness")
    public BranchReadiness readiness(@PathVariable UUID branchId) {
        return readinessService.evaluate(branchId);
    }

    @GetMapping("/{branchId}/settings")
    public BookingSettingsDto settings(@PathVariable UUID branchId) {
        return configService.getBookingSettings(branchId);
    }

    @PutMapping("/{branchId}/settings")
    public BookingSettingsDto updateSettings(@PathVariable UUID branchId,
                                             @Valid @RequestBody BookingSettingsUpdate request) {
        return configService.configureBookingSettings(branchId, request);
    }

    @GetMapping("/{branchId}/hours")
    public List<BranchHoursDto> hours(@PathVariable UUID branchId) {
        return configService.getOpeningHours(branchId);
    }

    @PutMapping("/{branchId}/hours")
    public void updateHours(@PathVariable UUID branchId,
                            @Valid @RequestBody BookingHoursRequest request) {
        configService.configureHours(branchId, request);
    }

    @GetMapping("/{branchId}/services")
    public List<ServiceOfferingDto> services(@PathVariable UUID branchId) {
        return configService.listOfferings(branchId);
    }

    @PutMapping("/{branchId}/services/{serviceCode}")
    public void updateService(@PathVariable UUID branchId,
                              @PathVariable String serviceCode,
                              @Valid @RequestBody BranchOfferingRequest request) {
        configService.configureOffering(branchId, serviceCode, request);
    }

    @GetMapping("/{branchId}/machines")
    public List<MachineDto> machines(@PathVariable UUID branchId) {
        return machineCatalogService.listByBranch(branchId);
    }

    @GetMapping("/{branchId}/resources")
    public List<ResourceDto> resources(@PathVariable UUID branchId) {
        return configService.listResources(branchId);
    }

    @PostMapping("/{branchId}/resources")
    public ResourceDto createResource(@PathVariable UUID branchId,
                                      @Valid @RequestBody CreateResourceRequest request) {
        return configService.addResource(branchId, request);
    }

    @PutMapping("/{branchId}/resources/{resourceId}")
    public ResourceDto updateResource(@PathVariable UUID branchId,
                                      @PathVariable UUID resourceId,
                                      @Valid @RequestBody ResourceUpdate request) {
        return configService.updateResource(branchId, resourceId, request);
    }
}
