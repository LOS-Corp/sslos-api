package com.laundry.booking;

import com.laundry.booking.dto.BookingDtos.CreateHoldRequest;
import com.laundry.booking.service.BookingAvailabilityService;
import com.laundry.booking.service.ScheduledBookingService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** BR-26 (declared load) and BR-27 (advance-booking quota) on PostgreSQL. */
@IntegrationTest
class BookingRulesIT {

    @Autowired private ScheduledBookingService bookings;
    @Autowired private BookingAvailabilityService availability;
    @Autowired private JdbcTemplate jdbc;

    private UUID branchId;
    private Instant tomorrow;

    @BeforeEach
    void setUp() {
        branchId = Fixtures.bookableBranch(jdbc, 30_000);
        tomorrow = LocalDate.now(Fixtures.ZONE).plusDays(1).atTime(9, 0).atZone(Fixtures.ZONE).toInstant();
    }

    @Test
    @DisplayName("SC-42: 10 kg declared, only a 7 kg washer → refused, search marks it TOO_SMALL; a 10 kg washer fits")
    void declaredLoadNeedsABigEnoughMachine() {
        UUID small = washer(new BigDecimal("7"));
        UUID big = washer(new BigDecimal("10"));

        assertThatThrownBy(() -> hold(tomorrow, small, new BigDecimal("10")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.MACHINE_TOO_SMALL));
        assertThat(hold(tomorrow, big, new BigDecimal("10"))).isNotNull();

        var search = availability.machineSearch(branchId, "WASH_ONLY", tomorrow, tomorrow.plus(2, ChronoUnit.HOURS),
                null, null, new BigDecimal("10"));
        assertThat(search.unavailable()).anySatisfy(machine -> {
            assertThat(machine.resourceId()).isEqualTo(small);
            assertThat(machine.reason()).isEqualTo("TOO_SMALL");
        });
        assertThat(search.options()).noneSatisfy(option ->
                assertThat(option.resources().getFirst().resourceId()).isEqualTo(small));
    }

    @Test
    @DisplayName("SC-47: with 3 washers, book-later fills at most 2 per slot (70 %); use-now still gets the third")
    void advanceQuotaKeepsMachinesForUseNow() {
        UUID w1 = washer(null);
        UUID w2 = washer(null);
        UUID w3 = washer(null);

        hold(tomorrow, w1, null);
        hold(tomorrow, w2, null);
        assertThatThrownBy(() -> hold(tomorrow, w3, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.ADVANCE_QUOTA_REACHED));

        // Another slot of the same day has its own quota.
        assertThat(hold(tomorrow.plus(3, ChronoUnit.HOURS), w3, null)).isNotNull();
        // Use-now (start within 15 minutes) is never limited by the quota.
        Instant now = Instant.now().plus(2, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MINUTES);
        hold(now, w1, null);
        hold(now, w2, null);
        assertThat(hold(now, w3, null)).isNotNull();
    }

    @Test
    @DisplayName("BR-05 / MSG09: a start more than 48 hours ahead is refused, in search and in the hold")
    void bookingHorizonIs48Hours() {
        UUID washer = washer(null);
        Instant tooFar = Instant.now().plus(49, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MINUTES);

        assertThatThrownBy(() -> hold(tooFar, washer, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.BEYOND_ADVANCE_HORIZON);
                    assertThat(((BusinessException) e).code().messageCode()).isEqualTo("MSG09");
                });
        assertThatThrownBy(() -> availability.machineSearch(branchId, "WASH_ONLY", tooFar,
                tooFar.plus(1, ChronoUnit.HOURS), null, null, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> availability.timelineAvailability(branchId, "WASH_ONLY",
                LocalDate.now(Fixtures.ZONE).plusDays(5), null, null))
                .isInstanceOf(BusinessException.class);
    }

    private Object hold(Instant start, UUID washer, BigDecimal load) {
        return bookings.createHold(UUID.randomUUID(), new CreateHoldRequest(branchId, "WASH_ONLY", start,
                List.of(washer), "rules-" + UUID.randomUUID(), null, null, load));
    }

    private UUID washer(BigDecimal capacityKg) {
        UUID id = Fixtures.washer(jdbc, branchId);
        jdbc.update("UPDATE machines SET capacity_kg = ? WHERE id = ?", capacityKg, id);
        return id;
    }
}
