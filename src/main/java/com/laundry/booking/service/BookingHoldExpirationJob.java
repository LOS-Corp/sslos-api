package com.laundry.booking.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.booking.expiration-enabled", havingValue = "true", matchIfMissing = true)
public class BookingHoldExpirationJob {
    private final ScheduledBookingService bookingService;

    @Scheduled(fixedDelayString = "${app.booking.expiration-interval-ms:30000}")
    public void expireBookingHolds() {
        bookingService.expireBookingHolds();
    }
}
