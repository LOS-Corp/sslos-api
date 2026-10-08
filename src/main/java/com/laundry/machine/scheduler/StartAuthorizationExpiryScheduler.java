package com.laundry.machine.scheduler;

import com.laundry.machine.service.MachineStartAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Background sweeper that flips ACTIVE {@link com.laundry.machine.entity.MachineStartAuthorization}
 * rows whose {@code expiresAt} has passed into {@code EXPIRED} and tells
 * the matching ESP32 to drop the authorization.
 *
 * <p>Runs every 5 seconds — short enough that a stuck device never holds
 * the physical START button open for more than a few seconds past deadline,
 * long enough that the query stays cheap.
 *
 * <p>Concurrency-safe: the underlying UPDATE is conditional on the row
 * still being in an ACTIVE state, so multiple instances (or a manual retry)
 * cannot double-flip a row.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StartAuthorizationExpiryScheduler {

    private final MachineStartAuthorizationService startAuthorizationService;

    /**
     * Sweep expiry every 5 s.
     * {@code initialDelay} keeps the bean quiet during the @PostConstruct storm.
     */
    @Scheduled(fixedDelayString = "${app.machine.start-authorization.sweep-millis:5000}",
               initialDelay = 10_000)
    public void sweep() {
        try {
            int n = startAuthorizationService.sweepExpired(OffsetDateTime.now());
            if (n > 0) {
                log.debug("Start-authorization expiry sweep flipped {} row(s)", n);
            }
        } catch (Exception e) {
            log.error("Start-authorization expiry sweep failed: {}", e.getMessage(), e);
        }
    }
}
