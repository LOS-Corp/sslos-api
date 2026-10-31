package com.laundry.booking.service;

import com.laundry.shared.scheduling.JobLock;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.booking.expiration-enabled", havingValue = "true", matchIfMissing = true)
public class BookingHoldExpirationJob {
    private final ScheduledBookingService bookingService;
    private final JobLock jobLock;

    @Scheduled(fixedDelayString = "${app.booking.expiration-interval-ms:30000}")
    public void expireBookingHolds() {
        jobLock.runExclusive("booking-hold-expiry", bookingService::expireBookingHolds);
    }
}
