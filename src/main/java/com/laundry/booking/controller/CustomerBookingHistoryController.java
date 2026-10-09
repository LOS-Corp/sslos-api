package com.laundry.booking.controller;

import com.laundry.booking.dto.BookingDtos.BookingDto;
import com.laundry.booking.service.ScheduledBookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users/me/bookings")
@RequiredArgsConstructor
public class CustomerBookingHistoryController {
    private final ScheduledBookingService bookingService;

    @GetMapping
    public List<BookingDto> getMyBookings(Authentication authentication) {
        if (authentication == null || authentication.getName() == null
                || authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer account is required");
        }
        try {
            return bookingService.getCustomerBookings(UUID.fromString(authentication.getName()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid customer identity");
        }
    }
}
