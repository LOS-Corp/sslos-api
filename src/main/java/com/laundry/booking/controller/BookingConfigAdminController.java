package com.laundry.booking.controller;

import com.laundry.booking.dto.BookingConfigDtos.BookingSettingsDto;
import com.laundry.booking.dto.BookingConfigDtos.BookingSettingsUpdate;
import com.laundry.booking.dto.BookingConfigDtos.StoreReadiness;
import com.laundry.booking.dto.BookingConfigDtos.ResourceUpdate;
import com.laundry.booking.dto.BookingConfigDtos.ServiceOfferingDto;
import com.laundry.booking.dto.BookingDtos.BookingHoursRequest;
import com.laundry.booking.dto.BookingDtos.OpeningHoursDto;
import com.laundry.booking.dto.BookingDtos.ServiceOfferingRequest;
import com.laundry.booking.dto.BookingDtos.CreateResourceRequest;
import com.laundry.booking.dto.BookingDtos.ResourceDto;
import com.laundry.booking.service.StoreBookingReadinessService;
import com.laundry.booking.service.StoreBookingConfigService;
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
@RequestMapping("/api/admin/booking-config")
@RequiredArgsConstructor
public class BookingConfigAdminController {
    private final StoreBookingReadinessService readinessService;
    private final StoreBookingConfigService configService;
    private final MachineCatalogService machineCatalogService;

    /** The store's booking configuration and whether customers can book (BR-28). */
    @GetMapping
    public StoreReadiness configuration() {
        return readinessService.evaluate();
    }

    @GetMapping("/readiness")
    public StoreReadiness readiness() {
        return readinessService.evaluate();
    }

    @GetMapping("/settings")
    public BookingSettingsDto settings() {
        return configService.getBookingSettings();
    }

    @PutMapping("/settings")
    public BookingSettingsDto updateSettings(@Valid @RequestBody BookingSettingsUpdate request) {
        return configService.configureBookingSettings(request);
    }

    @GetMapping("/hours")
    public List<OpeningHoursDto> hours() {
        return configService.getOpeningHours();
    }

    @PutMapping("/hours")
    public void updateHours(@Valid @RequestBody BookingHoursRequest request) {
        configService.configureHours(request);
    }

    @GetMapping("/services")
    public List<ServiceOfferingDto> services() {
        return configService.listOfferings();
    }

    @PutMapping("/services/{serviceCode}")
    public void updateService(@PathVariable String serviceCode,
                              @Valid @RequestBody ServiceOfferingRequest request) {
        configService.configureOffering(serviceCode, request);
    }

    @GetMapping("/machines")
    public List<MachineDto> machines() {
        return machineCatalogService.listAll();
    }

    @GetMapping("/resources")
    public List<ResourceDto> resources() {
        return configService.listResources();
    }

    @PostMapping("/resources")
    public ResourceDto createResource(@Valid @RequestBody CreateResourceRequest request) {
        return configService.addResource(request);
    }

    @PutMapping("/resources/{resourceId}")
    public ResourceDto updateResource(
                                      @PathVariable UUID resourceId,
                                      @Valid @RequestBody ResourceUpdate request) {
        return configService.updateResource(resourceId, request);
    }
}
