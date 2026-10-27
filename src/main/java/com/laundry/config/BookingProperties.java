package com.laundry.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Booking parameters of SRS 5.1 ("single source of values for code, UI and diagrams"), bound from
 * {@code app.booking.*}. Defaults are the adopted values; a wrong value stops the application at start-up.
 */
@Validated
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(
        /* BR-04: hold before payment. */
        @DefaultValue("300") @Min(60) @Max(3600) long holdDurationSeconds,
        /* BR-05: book-later start at most this far ahead. */
        @DefaultValue("48h") @NotNull Duration maxAdvance,
        /* A start closer than this to the hold is a use-now order (BR-05/07). */
        @DefaultValue("15m") @NotNull Duration useNowWindow,
        /* BR-07: check-in opens T−15 … closes T+10 (PAID+10 for use-now, BR-06). */
        @DefaultValue("15m") @NotNull Duration checkInOpensBefore,
        @DefaultValue("10m") @NotNull Duration checkInClosesAfter,
        /* BR-17: assisted drop-off — the locker PIN works until T+60, then the booking is a no-show. */
        @DefaultValue("60m") @NotNull Duration assistedDropOffWindow,
        /* BR-11: free cancellation until T−60. */
        @DefaultValue("60m") @NotNull Duration freeCancellationBefore,
        /* BR-27: share of a slot's machines open to book-later orders. */
        @DefaultValue("70") @Min(0) @Max(100) int advanceQuotaPercent,
        /* Assumed remaining time of a running cycle when the device has not reported one. */
        @DefaultValue("60") @Min(1) long activeSessionFallbackMinutes,
        /* Grace after the window before a finished reservation is closed out. */
        @DefaultValue("15") @Min(0) long completionGraceMinutes) {

    public Duration holdDuration() {
        return Duration.ofSeconds(holdDurationSeconds);
    }
}
