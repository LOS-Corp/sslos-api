package com.laundry.booking.controller;

import com.laundry.booking.dto.BookingDtos.ServiceDto;
import com.laundry.booking.dto.MachineProgramDtos.MachineProgramDto;
import com.laundry.booking.service.MachineProgramService;
import com.laundry.booking.service.ScheduledBookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
public class ServiceCatalogController {
    private final ScheduledBookingService bookingService;
    private final MachineProgramService programService;

    @GetMapping
    public java.util.List<ServiceDto> list() {
        return bookingService.listServices();
    }

    /** Active wash / dry programs, optionally of one machine type (WASHER / DRYER). */
    @GetMapping("/programs")
    public java.util.List<MachineProgramDto> programs(@RequestParam(required = false) String machineType) {
        return programService.listActive(machineType);
    }

    @GetMapping("/{code}")
    public ServiceDto get(@PathVariable String code) {
        return bookingService.listServices().stream()
                .filter(service -> service.code().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found"));
    }
}
