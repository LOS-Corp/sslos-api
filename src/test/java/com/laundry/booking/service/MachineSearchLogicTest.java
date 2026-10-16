package com.laundry.booking.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure scheduling rules behind {@code ScheduledBookingService#machineSearch}. */
class MachineSearchLogicTest {
    private static final ZoneId HCM = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Instant START = Instant.parse("2026-10-09T02:00:00Z"); // 09:00 local

    @Test
    void turnoverBufferExtendsResourceOccupancyButNotExpectedCompletion() {
        ScheduledBookingService.SlotPlan plan = ScheduledBookingService.buildPlan(washOnly(10), START);

        // 5 check-in + 35 wash + 5 checkout = 45 minutes, buffer adds 10 to the occupancy only.
        assertEquals(START.plusSeconds(45 * 60), plan.expectedEnd());
        assertEquals(START.plusSeconds(55 * 60), plan.allocationPlans().getFirst().until());
    }

    @Test
    void zeroBufferKeepsPreviousOccupancy() {
        ScheduledBookingService.SlotPlan plan = ScheduledBookingService.buildPlan(washOnly(0), START);

        assertEquals(plan.expectedEnd(), plan.allocationPlans().getFirst().until());
    }

    @Test
    void earliestStartComesRightAfterExistingBookingPlusBuffer() {
        UUID washer = UUID.randomUUID();
        ScheduledBookingService.Resource resource = new ScheduledBookingService.Resource("WASHER", washer);
        // Existing booking occupies 09:00–09:55 (already including its own buffer).
        Map<UUID, List<ScheduledBookingService.OccupiedInterval>> occupied = Map.of(washer,
                List.of(new ScheduledBookingService.OccupiedInterval(START, START.plusSeconds(55 * 60))));
        ScheduledBookingService.ServiceConfig config = washOnly(10);

        Instant earliest = null;
        for (int minute = 0; minute < 180 && earliest == null; minute++) {
            ScheduledBookingService.SlotPlan plan =
                    ScheduledBookingService.buildPlan(config, START.plusSeconds(minute * 60L));
            if (ScheduledBookingService.freeCombination(resource, plan, config.requirements(),
                    Map.of("WASHER", List.of(resource)), occupied) != null) {
                earliest = plan.start();
            }
        }
        // Minute precision: 09:55 exactly, not rounded to a 15/30-minute slot.
        assertEquals(START.plusSeconds(55 * 60), earliest);
    }

    @Test
    void comboPairsWasherWithFirstFreeDryer() {
        UUID washer = UUID.randomUUID();
        UUID busyDryer = UUID.randomUUID();
        UUID freeDryer = UUID.randomUUID();
        ScheduledBookingService.ServiceConfig config = washDry(0);
        ScheduledBookingService.SlotPlan plan = ScheduledBookingService.buildPlan(config, START);
        ScheduledBookingService.AllocationPlan dryerSlot = plan.allocationPlans().get(1);
        Map<String, List<ScheduledBookingService.Resource>> resources = Map.of(
                "WASHER", List.of(new ScheduledBookingService.Resource("WASHER", washer)),
                "DRYER", List.of(new ScheduledBookingService.Resource("DRYER", busyDryer),
                        new ScheduledBookingService.Resource("DRYER", freeDryer)));
        Map<UUID, List<ScheduledBookingService.OccupiedInterval>> occupied = Map.of(busyDryer,
                List.of(new ScheduledBookingService.OccupiedInterval(dryerSlot.from(), dryerSlot.until())));

        List<ScheduledBookingService.Resource> combo = ScheduledBookingService.freeCombination(
                resources.get("WASHER").getFirst(), plan, config.requirements(), resources, occupied);

        assertEquals(2, combo.size());
        assertEquals(freeDryer, combo.get(1).id());
        assertTrue(ScheduledBookingService.isCombinationFree(combo, plan, occupied));
    }

    @Test
    void comboIsUnavailableWhenNoDryerIsFree() {
        UUID washer = UUID.randomUUID();
        UUID dryer = UUID.randomUUID();
        ScheduledBookingService.ServiceConfig config = washDry(0);
        ScheduledBookingService.SlotPlan plan = ScheduledBookingService.buildPlan(config, START);
        ScheduledBookingService.AllocationPlan dryerSlot = plan.allocationPlans().get(1);
        Map<String, List<ScheduledBookingService.Resource>> resources = Map.of(
                "WASHER", List.of(new ScheduledBookingService.Resource("WASHER", washer)),
                "DRYER", List.of(new ScheduledBookingService.Resource("DRYER", dryer)));

        assertNull(ScheduledBookingService.freeCombination(resources.get("WASHER").getFirst(), plan,
                config.requirements(), resources, Map.of(dryer, List.of(
                        new ScheduledBookingService.OccupiedInterval(dryerSlot.from(), dryerSlot.until())))));
    }

    @Test
    void overnightOpeningHoursCoverServiceThatCrossesMidnight() {
        Map<DayOfWeek, ScheduledBookingService.ScheduleHours> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            hours.put(day, new ScheduledBookingService.ScheduleHours(LocalTime.of(18, 0), LocalTime.of(2, 0)));
        }
        Instant lateStart = Instant.parse("2026-10-09T16:40:00Z"); // 23:40 local
        assertTrue(ScheduledBookingService.isCoveredByOpeningHours(
                hours, HCM, lateStart, lateStart.plusSeconds(45 * 60)));
        Instant tooLate = Instant.parse("2026-10-09T18:30:00Z"); // 01:30 local, ends after 02:00
        assertFalse(ScheduledBookingService.isCoveredByOpeningHours(
                hours, HCM, tooLate, tooLate.plusSeconds(45 * 60)));
    }

    @Test
    void allDayHoursBehaveAsTwentyFourSeven() {
        Map<DayOfWeek, ScheduledBookingService.ScheduleHours> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            hours.put(day, new ScheduledBookingService.ScheduleHours(LocalTime.MIDNIGHT, LocalTime.of(23, 59)));
        }
        Instant aroundMidnight = Instant.parse("2026-10-09T16:50:00Z"); // 23:50 local
        assertTrue(ScheduledBookingService.isCoveredByOpeningHours(
                hours, HCM, aroundMidnight, aroundMidnight.plusSeconds(3 * 3600)));
    }

    @Test
    void searchStartRoundsUpToTheNextWholeMinute() {
        assertEquals(Instant.parse("2026-10-09T02:01:00Z"),
                ScheduledBookingService.ceilToMinute(Instant.parse("2026-10-09T02:00:00.001Z")));
        assertEquals(Instant.parse("2026-10-09T02:01:00Z"),
                ScheduledBookingService.ceilToMinute(Instant.parse("2026-10-09T02:00:30Z")));
        assertEquals(START, ScheduledBookingService.ceilToMinute(START));
    }

    private static ScheduledBookingService.ServiceConfig washOnly(int bufferMinutes) {
        return new ScheduledBookingService.ServiceConfig(
                "WASH_ONLY", "Wash", "SELF_SERVICE", "", 35, 0,
                5, 5, 0, 0, BigDecimal.TEN, UUID.randomUUID(), "Asia/Ho_Chi_Minh",
                LocalTime.MIN, LocalTime.MAX, List.of("WASHER"), bufferMinutes);
    }

    private static ScheduledBookingService.ServiceConfig washDry(int bufferMinutes) {
        return new ScheduledBookingService.ServiceConfig(
                "WASH_DRY", "Wash and dry", "SELF_SERVICE", "", 35, 45,
                5, 5, 10, 0, BigDecimal.TEN, UUID.randomUUID(), "Asia/Ho_Chi_Minh",
                LocalTime.MIN, LocalTime.MAX, List.of("WASHER", "DRYER"), bufferMinutes);
    }
}
