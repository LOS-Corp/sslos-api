package com.laundry.booking.service;

import com.laundry.booking.dto.BookingDtos;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingResourceAvailabilityTest {
    private static final Instant START = Instant.parse("2026-10-09T09:12:00Z");
    private static final Instant CHECK_IN_END = Instant.parse("2026-10-09T09:17:00Z");
    private static final Instant WASH_END = Instant.parse("2026-10-09T09:52:00Z");
    private static final Instant TRANSFER_END = Instant.parse("2026-10-09T10:02:00Z");
    private static final Instant WASH_ONLY_END = Instant.parse("2026-10-09T09:57:00Z");
    private static final Instant DRY_END = Instant.parse("2026-10-09T10:52:00Z");

    @Test
    void permitsMinutePreciseStartWhenOneOfSeveralWashersIsFree() {
        UUID occupiedWasher = UUID.randomUUID();
        UUID freeWasher = UUID.randomUUID();
        assertTrue(available(
                List.of(allocation("WASHER", START, DRY_END)),
                Map.of("WASHER", List.of(resource("WASHER", occupiedWasher),
                        resource("WASHER", freeWasher))),
                Map.of(occupiedWasher, List.of(occupied(START, DRY_END)))));
    }

    @Test
    void rejectsWhenEveryCompatibleWasherIsOccupied() {
        UUID washer = UUID.randomUUID();
        assertFalse(available(
                List.of(allocation("WASHER", START, DRY_END)),
                Map.of("WASHER", List.of(resource("WASHER", washer))),
                Map.of(washer, List.of(occupied(START, DRY_END)))));
    }

    @Test
    void rejectsWashDryWhenTheDryerIsUnavailableEvenIfWasherIsFree() {
        UUID washer = UUID.randomUUID();
        UUID dryer = UUID.randomUUID();
        assertFalse(available(
                List.of(allocation("WASHER", START, TRANSFER_END),
                        allocation("DRYER", TRANSFER_END, DRY_END)),
                Map.of("WASHER", List.of(resource("WASHER", washer)),
                        "DRYER", List.of(resource("DRYER", dryer))),
                Map.of(dryer, List.of(occupied(TRANSFER_END, DRY_END)))));
    }

    @Test
    void washerMustRemainAvailableThroughTransferButDryerCanBeBookedAtTransferEnd() {
        UUID washer = UUID.randomUUID();
        UUID dryer = UUID.randomUUID();
        List<ScheduledBookingService.AllocationPlan> combo = List.of(
                allocation("WASHER", START, TRANSFER_END),
                allocation("DRYER", TRANSFER_END, DRY_END));
        Map<String, List<ScheduledBookingService.Resource>> resources = Map.of(
                "WASHER", List.of(resource("WASHER", washer)),
                "DRYER", List.of(resource("DRYER", dryer)));

        assertFalse(available(combo, resources,
                Map.of(washer, List.of(occupied(WASH_END, TRANSFER_END)))));
        assertTrue(available(combo, resources,
                Map.of(dryer, List.of(occupied(WASH_END, TRANSFER_END)))));
    }

    @Test
    void washDryPlanStartsDryerOnlyAfterTransferAndReservesBothResourcesSeparately() {
        ScheduledBookingService.ServiceConfig config = new ScheduledBookingService.ServiceConfig(
                "WASH_DRY", "Wash and dry", "SELF_SERVICE", "", 35, 45,
                5, 5, 10, 0, BigDecimal.ONE, UUID.randomUUID(), "UTC",
                LocalTime.MIN, LocalTime.MAX, List.of("WASHER", "DRYER"));

        ScheduledBookingService.SlotPlan plan = ScheduledBookingService.buildPlan(config, START);

        assertEquals(2, plan.allocationPlans().size());
        assertEquals(START, plan.allocationPlans().get(0).from());
        assertEquals(TRANSFER_END, plan.allocationPlans().get(0).until());
        assertEquals(TRANSFER_END, plan.allocationPlans().get(1).from());
        assertEquals(DRY_END, plan.allocationPlans().get(1).until());
    }

    @Test
    void washOnlyPlanKeepsBackendConfiguredDurationAtMinutePrecision() {
        ScheduledBookingService.ServiceConfig config = new ScheduledBookingService.ServiceConfig(
                "WASH_ONLY", "Wash", "SELF_SERVICE", "", 35, 0,
                5, 5, 0, 0, BigDecimal.ONE, UUID.randomUUID(), "UTC",
                LocalTime.MIN, LocalTime.MAX, List.of("WASHER"));

        ScheduledBookingService.SlotPlan plan = ScheduledBookingService.buildPlan(config, START);

        assertEquals(CHECK_IN_END, plan.checkInEnd());
        assertEquals(WASH_END, plan.primaryEnd());
        assertEquals(WASH_ONLY_END, plan.expectedEnd());
    }

    @Test
    void treatsAdjacentReservationsAsNonOverlapping() {
        assertFalse(ScheduledBookingService.intervalsOverlap(
                START, WASH_END, WASH_END, TRANSFER_END));
        assertTrue(ScheduledBookingService.intervalsOverlap(
                START, TRANSFER_END, WASH_END, DRY_END));
    }

    @Test
    void validatesOneMinutePrecisionWithoutRounding() {
        assertTrue(ScheduledBookingService.isMinuteAligned(START));
        assertFalse(ScheduledBookingService.isMinuteAligned(START.plusSeconds(1)));
        assertFalse(ScheduledBookingService.isMinuteAligned(START.plusNanos(1)));
    }

    @Test
    void returnsContinuousMinuteRangesInsteadOfPredefinedSlotLists() {
        List<BookingDtos.AvailableStartRange> ranges = ScheduledBookingService.toStartRanges(List.of(
                Instant.parse("2026-10-09T09:12:00Z"),
                Instant.parse("2026-10-09T09:13:00Z"),
                Instant.parse("2026-10-09T09:15:00Z")));

        assertEquals(2, ranges.size());
        assertEquals(Instant.parse("2026-10-09T09:12:00Z"), ranges.get(0).startTime());
        assertEquals(Instant.parse("2026-10-09T09:14:00Z"), ranges.get(0).endExclusive());
        assertEquals(Instant.parse("2026-10-09T09:15:00Z"), ranges.get(1).startTime());
        assertEquals(Instant.parse("2026-10-09T09:16:00Z"), ranges.get(1).endExclusive());
    }

    @Test
    void branchLocalDayIntervalsRespectDaylightSavingTransitions() {
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant springStart = LocalDate.of(2026, 3, 8).atStartOfDay(newYork).toInstant();
        Instant springEnd = LocalDate.of(2026, 3, 9).atStartOfDay(newYork).toInstant();
        Instant fallStart = LocalDate.of(2026, 11, 1).atStartOfDay(newYork).toInstant();
        Instant fallEnd = LocalDate.of(2026, 11, 2).atStartOfDay(newYork).toInstant();

        assertEquals(23, Duration.between(springStart, springEnd).toHours());
        assertEquals(25, Duration.between(fallStart, fallEnd).toHours());
    }

    private static boolean available(
            List<ScheduledBookingService.AllocationPlan> allocations,
            Map<String, List<ScheduledBookingService.Resource>> resources,
            Map<UUID, List<ScheduledBookingService.OccupiedInterval>> occupied) {
        return ScheduledBookingService.hasAvailableResourceCombination(allocations, resources, occupied);
    }

    private static ScheduledBookingService.AllocationPlan allocation(
            String type, Instant from, Instant until) {
        return new ScheduledBookingService.AllocationPlan(type, type, from, until);
    }

    private static ScheduledBookingService.Resource resource(String type, UUID id) {
        return new ScheduledBookingService.Resource(type, id);
    }

    private static ScheduledBookingService.OccupiedInterval occupied(Instant from, Instant until) {
        return new ScheduledBookingService.OccupiedInterval(from, until);
    }
}
