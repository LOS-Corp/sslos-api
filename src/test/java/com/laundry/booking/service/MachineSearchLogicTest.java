package com.laundry.booking.service;

import com.laundry.booking.domain.SlotPlanner;
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
        SlotPlanner.SlotPlan plan = SlotPlanner.buildPlan(washOnly(10), START);

        // 5 check-in + 35 wash + 5 checkout = 45 minutes, buffer adds 10 to the occupancy only.
        assertEquals(START.plusSeconds(45 * 60), plan.expectedEnd());
        assertEquals(START.plusSeconds(55 * 60), plan.allocationPlans().getFirst().until());
    }

    @Test
    void zeroBufferKeepsPreviousOccupancy() {
        SlotPlanner.SlotPlan plan = SlotPlanner.buildPlan(washOnly(0), START);

        assertEquals(plan.expectedEnd(), plan.allocationPlans().getFirst().until());
    }

    @Test
    void earliestStartComesRightAfterExistingBookingPlusBuffer() {
        UUID washer = UUID.randomUUID();
        SlotPlanner.Resource resource = new SlotPlanner.Resource("WASHER", washer);
        // Existing booking occupies 09:00–09:55 (already including its own buffer).
        Map<UUID, List<SlotPlanner.OccupiedInterval>> occupied = Map.of(washer,
                List.of(new SlotPlanner.OccupiedInterval(START, START.plusSeconds(55 * 60))));
        SlotPlanner.ServiceConfig config = washOnly(10);

        Instant earliest = null;
        for (int minute = 0; minute < 180 && earliest == null; minute++) {
            SlotPlanner.SlotPlan plan =
                    SlotPlanner.buildPlan(config, START.plusSeconds(minute * 60L));
            if (SlotPlanner.freeCombination(resource, plan, config.requirements(),
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
        SlotPlanner.ServiceConfig config = washDry(0);
        SlotPlanner.SlotPlan plan = SlotPlanner.buildPlan(config, START);
        SlotPlanner.AllocationPlan dryerSlot = plan.allocationPlans().get(1);
        Map<String, List<SlotPlanner.Resource>> resources = Map.of(
                "WASHER", List.of(new SlotPlanner.Resource("WASHER", washer)),
                "DRYER", List.of(new SlotPlanner.Resource("DRYER", busyDryer),
                        new SlotPlanner.Resource("DRYER", freeDryer)));
        Map<UUID, List<SlotPlanner.OccupiedInterval>> occupied = Map.of(busyDryer,
                List.of(new SlotPlanner.OccupiedInterval(dryerSlot.from(), dryerSlot.until())));

        List<SlotPlanner.Resource> combo = SlotPlanner.freeCombination(
                resources.get("WASHER").getFirst(), plan, config.requirements(), resources, occupied);

        assertEquals(2, combo.size());
        assertEquals(freeDryer, combo.get(1).id());
        assertTrue(SlotPlanner.isCombinationFree(combo, plan, occupied));
    }

    @Test
    void comboIsUnavailableWhenNoDryerIsFree() {
        UUID washer = UUID.randomUUID();
        UUID dryer = UUID.randomUUID();
        SlotPlanner.ServiceConfig config = washDry(0);
        SlotPlanner.SlotPlan plan = SlotPlanner.buildPlan(config, START);
        SlotPlanner.AllocationPlan dryerSlot = plan.allocationPlans().get(1);
        Map<String, List<SlotPlanner.Resource>> resources = Map.of(
                "WASHER", List.of(new SlotPlanner.Resource("WASHER", washer)),
                "DRYER", List.of(new SlotPlanner.Resource("DRYER", dryer)));

        assertNull(SlotPlanner.freeCombination(resources.get("WASHER").getFirst(), plan,
                config.requirements(), resources, Map.of(dryer, List.of(
                        new SlotPlanner.OccupiedInterval(dryerSlot.from(), dryerSlot.until())))));
    }

    @Test
    void overnightOpeningHoursCoverServiceThatCrossesMidnight() {
        Map<DayOfWeek, SlotPlanner.ScheduleHours> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            hours.put(day, new SlotPlanner.ScheduleHours(LocalTime.of(18, 0), LocalTime.of(2, 0)));
        }
        Instant lateStart = Instant.parse("2026-10-09T16:40:00Z"); // 23:40 local
        assertTrue(SlotPlanner.isCoveredByOpeningHours(
                hours, HCM, lateStart, lateStart.plusSeconds(45 * 60)));
        Instant tooLate = Instant.parse("2026-10-09T18:30:00Z"); // 01:30 local, ends after 02:00
        assertFalse(SlotPlanner.isCoveredByOpeningHours(
                hours, HCM, tooLate, tooLate.plusSeconds(45 * 60)));
    }

    @Test
    void allDayHoursBehaveAsTwentyFourSeven() {
        Map<DayOfWeek, SlotPlanner.ScheduleHours> hours = new EnumMap<>(DayOfWeek.class);
        for (DayOfWeek day : DayOfWeek.values()) {
            hours.put(day, new SlotPlanner.ScheduleHours(LocalTime.MIDNIGHT, LocalTime.of(23, 59)));
        }
        Instant aroundMidnight = Instant.parse("2026-10-09T16:50:00Z"); // 23:50 local
        assertTrue(SlotPlanner.isCoveredByOpeningHours(
                hours, HCM, aroundMidnight, aroundMidnight.plusSeconds(3 * 3600)));
    }

    @Test
    void searchStartRoundsUpToTheNextWholeMinute() {
        assertEquals(Instant.parse("2026-10-09T02:01:00Z"),
                SlotPlanner.ceilToMinute(Instant.parse("2026-10-09T02:00:00.001Z")));
        assertEquals(Instant.parse("2026-10-09T02:01:00Z"),
                SlotPlanner.ceilToMinute(Instant.parse("2026-10-09T02:00:30Z")));
        assertEquals(START, SlotPlanner.ceilToMinute(START));
    }

    private static SlotPlanner.ServiceConfig washOnly(int bufferMinutes) {
        return new SlotPlanner.ServiceConfig(
                "WASH_ONLY", "Wash", "SELF_SERVICE", "", 35, 0,
                5, 5, 0, 0, BigDecimal.TEN, UUID.randomUUID(), "Asia/Ho_Chi_Minh",
                LocalTime.MIN, LocalTime.MAX, List.of("WASHER"), bufferMinutes);
    }

    private static SlotPlanner.ServiceConfig washDry(int bufferMinutes) {
        return new SlotPlanner.ServiceConfig(
                "WASH_DRY", "Wash and dry", "SELF_SERVICE", "", 35, 45,
                5, 5, 10, 0, BigDecimal.TEN, UUID.randomUUID(), "Asia/Ho_Chi_Minh",
                LocalTime.MIN, LocalTime.MAX, List.of("WASHER", "DRYER"), bufferMinutes);
    }
}
