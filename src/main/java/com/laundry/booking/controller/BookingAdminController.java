package com.laundry.booking.controller;

import com.laundry.booking.dto.BookingDtos.BookingHoursRequest;
import com.laundry.booking.dto.BookingDtos.BranchOfferingRequest;
import com.laundry.booking.dto.BookingDtos.CreateResourceRequest;
import com.laundry.booking.dto.BookingDtos.ResourceDto;
import com.laundry.booking.service.BranchBookingConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/bookings/branches/{branchId}")
@RequiredArgsConstructor
public class BookingAdminController {
    private final BranchBookingConfigService configService;

    @PutMapping("/hours")
    public void configureHours(@PathVariable UUID branchId,
                               @Valid @RequestBody BookingHoursRequest request) {
        configService.configureHours(branchId, request);
    }

    @PutMapping("/services/{serviceCode}")
    public void configureService(@PathVariable UUID branchId,
                                 @PathVariable String serviceCode,
                                 @Valid @RequestBody BranchOfferingRequest request) {
        configService.configureOffering(branchId, serviceCode, request);
    }

    @GetMapping("/resources")
    public List<ResourceDto> resources(@PathVariable UUID branchId) {
        return configService.listResources(branchId);
    }

    @PostMapping("/resources")
    public ResourceDto addResource(@PathVariable UUID branchId,
                                   @Valid @RequestBody CreateResourceRequest request) {
        return configService.addResource(branchId, request);
    }

    @PutMapping("/resources/{resourceId}")
    public void setResourceEnabled(@PathVariable UUID branchId,
                                   @PathVariable UUID resourceId,
                                   @RequestParam boolean enabled) {
        configService.setResourceEnabled(branchId, resourceId, enabled);
    }
}
