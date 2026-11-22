package com.laundry.booking;

import com.laundry.booking.service.BookingAvailabilityService;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.machine.dto.PriceProfileDtos.PriceProfileRequest;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.service.PriceProfileService;
import com.laundry.shared.error.BusinessException;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V71: each washer holds its own cycle (its price profile), not the slowest washer of the store. */
@IntegrationTest
@DisplayName("Per-machine cycle")
class MachineCycleIT {

    @Autowired private PriceProfileService profiles;
    @Autowired private ScheduledBookingService bookings;
    @Autowired private BookingAvailabilityService availability;
    @Autowired private JdbcTemplate jdbc;

    private Instant start;

    @BeforeEach
    void setUp() {
        Fixtures.bookableStore(jdbc, 30_000);
        start = LocalDate.now(Fixtures.ZONE).plusDays(1).atTime(13, 0).atZone(Fixtures.ZONE).toInstant();
    }

    @Test
    @DisplayName("A 10 kg washer (35 min) and a 15 kg washer (50 min) are each held for their own cycle")
    void eachWasherHoldsItsOwnCycle() {
        UUID small = washer(35);
        UUID large = washer(50);

        var smallHold = hold(small, start);
        var largeHold = hold(large, start);
        assertThat(washing(smallHold)).isEqualTo(Duration.ofMinutes(35));
        assertThat(washing(largeHold)).isEqualTo(Duration.ofMinutes(50));
        // Check-in and check-out (5 + 5) stay around the machine's own run.
        assertThat(Duration.between(smallHold.startTime(), smallHold.expectedEndTime())).isEqualTo(Duration.ofMinutes(45));
        assertThat(Duration.between(largeHold.startTime(), largeHold.expectedEndTime())).isEqualTo(Duration.ofMinutes(60));

        // The small washer is free again 45 minutes later, not after the large one's 60.
        Instant next = start.plus(Duration.ofMinutes(45));
        var search = availability.machineSearch("WASH_ONLY", next, next.plusSeconds(600), null);
        assertThat(search.options()).filteredOn(o -> o.resources().getFirst().resourceId().equals(small)).singleElement()
                .satisfies(o -> {
                    assertThat(o.earliestStart()).isEqualTo(next);
                    assertThat(o.totalMinutes()).isEqualTo(45);
                });
    }

    @Test
    @DisplayName("A cycle under 10 minutes is refused: it would let bookings overlap the real run")
    void unrealisticCycleRefused() {
        assertThatThrownBy(() -> profiles.create(new PriceProfileRequest("Một phút " + suffix(), MachineType.WASHER,
                new BigDecimal("30000"), 1, null, null, null))).isInstanceOf(BusinessException.class);
    }

    private UUID washer(int cycleMinutes) {
        UUID washer = Fixtures.washer(jdbc);
        UUID profile = profiles.create(new PriceProfileRequest("Giặt " + cycleMinutes + " " + suffix(), MachineType.WASHER,
                new BigDecimal("30000"), cycleMinutes, null, null, null)).id();
        jdbc.update("UPDATE machines SET price_profile_id = ? WHERE id = ?", profile, washer);
        return washer;
    }

    private com.laundry.booking.dto.BookingDtos.BookingDto hold(UUID washer, Instant at) {
        return bookings.createHold(UUID.randomUUID(), new CreateHoldRequest("WASH_ONLY", at, List.of(washer),
                "cycle-" + UUID.randomUUID(), null, null, null));
    }

    private static Duration washing(com.laundry.booking.dto.BookingDtos.BookingDto booking) {
        var phase = booking.timeline().stream().filter(p -> p.phase().equals("WASHING")).findFirst().orElseThrow();
        return Duration.between(phase.startTime(), phase.endTime());
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }
}
