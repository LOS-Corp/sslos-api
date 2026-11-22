package com.laundry.booking.controller;

import com.laundry.shared.error.BusinessException;
import com.laundry.booking.dto.BookingDtos.ServiceDto;
import com.laundry.booking.service.BookingAvailabilityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
public class ServiceCatalogController {
    private final BookingAvailabilityService availabilityService;

    @GetMapping
    public java.util.List<ServiceDto> list() {
        return availabilityService.listServices();
    }

    @GetMapping("/{code}")
    public ServiceDto get(@PathVariable String code) {
        return availabilityService.listServices().stream()
                .filter(service -> service.code().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Service not found"));
    }
}
