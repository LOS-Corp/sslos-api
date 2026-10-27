package com.laundry.booking.controller;

import com.laundry.shared.security.CurrentUser;
import com.laundry.booking.dto.BookingDtos.BookingDto;
import com.laundry.booking.service.ScheduledBookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users/me/bookings")
@RequiredArgsConstructor
public class CustomerBookingHistoryController {
    private final ScheduledBookingService bookingService;

    @GetMapping
    public List<BookingDto> getMyBookings(CurrentUser customer) {
        return bookingService.getCustomerBookings(customer.id());
    }
}
