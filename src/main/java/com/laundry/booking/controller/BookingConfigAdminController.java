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
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.machine.dto.MachineDto;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineService;
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
    private final ScheduledBookingService bookingService;
    private final MachineRepository machineRepository;
    private final MachineService machineService;

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
        return bookingService.getBookingSettings(branchId);
    }

    @PutMapping("/{branchId}/settings")
    public BookingSettingsDto updateSettings(@PathVariable UUID branchId,
                                             @Valid @RequestBody BookingSettingsUpdate request) {
        return bookingService.configureBookingSettings(branchId, request);
    }

    @GetMapping("/{branchId}/hours")
    public List<BranchHoursDto> hours(@PathVariable UUID branchId) {
        return bookingService.getOpeningHours(branchId);
    }

    @PutMapping("/{branchId}/hours")
    public void updateHours(@PathVariable UUID branchId,
                            @Valid @RequestBody BookingHoursRequest request) {
        bookingService.configureHours(branchId, request);
    }

    @GetMapping("/{branchId}/services")
    public List<ServiceOfferingDto> services(@PathVariable UUID branchId) {
        return bookingService.listOfferings(branchId);
    }

    @PutMapping("/{branchId}/services/{serviceCode}")
    public void updateService(@PathVariable UUID branchId,
                              @PathVariable String serviceCode,
                              @Valid @RequestBody BranchOfferingRequest request) {
        bookingService.configureOffering(branchId, serviceCode, request);
    }

    @GetMapping("/{branchId}/machines")
    public List<MachineDto> machines(@PathVariable UUID branchId) {
        return machineRepository.findByBranchId(branchId).stream()
                .map(machineService::toDto)
                .toList();
    }

    @GetMapping("/{branchId}/resources")
    public List<ResourceDto> resources(@PathVariable UUID branchId) {
        return bookingService.listResources(branchId);
    }

    @PostMapping("/{branchId}/resources")
    public ResourceDto createResource(@PathVariable UUID branchId,
                                      @Valid @RequestBody CreateResourceRequest request) {
        return bookingService.addResource(branchId, request);
    }

    @PutMapping("/{branchId}/resources/{resourceId}")
    public ResourceDto updateResource(@PathVariable UUID branchId,
                                      @PathVariable UUID resourceId,
                                      @Valid @RequestBody ResourceUpdate request) {
        return bookingService.updateResource(branchId, resourceId, request);
    }
}
