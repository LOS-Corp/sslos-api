package com.laundry.booking;

import com.laundry.booking.dto.BookingConfigDtos.BookingSettingsUpdate;
import com.laundry.booking.service.BookingAvailabilityService;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.booking.service.StoreBookingConfigService;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import static org.assertj.core.api.Assertions.assertThat;

/** V70: check-in, check-out and Wash–Dry transfer minutes are the store's settings, not fixed per service. */
@IntegrationTest
@DisplayName("Store check-in / check-out times")
class StoreTimingIT {

    @Autowired private StoreBookingConfigService config;
    @Autowired private BookingAvailabilityService availability;
    @Autowired private ScheduledBookingService bookings;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        Fixtures.bookableStore(jdbc, 30_000);
    }

    @Test
    @DisplayName("The owner's times apply to self-service bookings; assisted laundry keeps its own")
    void storeTimesApplyToSelfService() {
        var saved = config.configureBookingSettings(
                new BookingSettingsUpdate(Fixtures.ZONE.getId(), null, 12, 7, 15));
        assertThat(saved.checkInMinutes()).isEqualTo(12);
        assertThat(saved.checkoutMinutes()).isEqualTo(7);
        assertThat(saved.transferMinutes()).isEqualTo(15);
        // Null keeps the value.
        assertThat(config.configureBookingSettings(new BookingSettingsUpdate(Fixtures.ZONE.getId(), 3))
                .checkInMinutes()).isEqualTo(12);

        var services = availability.listServices();
        assertThat(services).filteredOn(s -> s.code().equals("WASH_ONLY")).singleElement().satisfies(s -> {
            assertThat(s.checkInMinutes()).isEqualTo(12);
            assertThat(s.checkoutMinutes()).isEqualTo(7);
            assertThat(s.transferMinutes()).as("one machine: nothing to move").isZero();
        });
        assertThat(services).filteredOn(s -> s.code().equals("WASH_DRY")).singleElement()
                .satisfies(s -> assertThat(s.transferMinutes()).isEqualTo(15));
        assertThat(services).filteredOn(s -> s.code().equals("ASSISTED_LOCKER")).singleElement()
                .satisfies(s -> assertThat(s.checkInMinutes()).isEqualTo(5));

        UUID washer = Fixtures.washer(jdbc);
        Instant start = LocalDate.now(Fixtures.ZONE).plusDays(1).atTime(9, 0).atZone(Fixtures.ZONE).toInstant();
        var booking = bookings.createHold(UUID.randomUUID(), new CreateHoldRequest("WASH_ONLY", start, List.of(washer),
                "timing-" + UUID.randomUUID(), null, null, null));
        var checkIn = booking.timeline().getFirst();
        assertThat(checkIn.phase()).isEqualTo("CHECK_IN");
        assertThat(Duration.between(checkIn.startTime(), checkIn.endTime())).isEqualTo(Duration.ofMinutes(12));
        var last = booking.timeline().getLast();
        assertThat(Duration.between(last.startTime(), last.endTime())).isEqualTo(Duration.ofMinutes(7));
    }
}
