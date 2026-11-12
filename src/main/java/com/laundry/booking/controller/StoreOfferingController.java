package com.laundry.booking.controller;

import com.laundry.booking.service.BookingAvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.laundry.booking.dto.BookingDtos.StoreOfferingDto;

/** What a customer can book at the store (public): price, durations and opening hours per service. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Store", description = "The store this server runs")
public class StoreOfferingController {

    private final BookingAvailabilityService availabilityService;

    @GetMapping("/api/store/offerings")
    @Operation(summary = "Services customers can book now; empty until the store is ready (BR-28)")
    public List<StoreOfferingDto> offerings() {
        return availabilityService.listOfferings();
    }
}
