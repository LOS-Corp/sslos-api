package com.laundry.booking.service;

import com.laundry.booking.domain.SlotPlanner;
import com.laundry.booking.dto.MachineProgramDtos.MachineProgramDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MachineProgramLogicTest {
    private static final Instant START = Instant.parse("2026-10-25T09:00:00Z");

    @Test
    void comboTakesWasherAndDryerDurationsAndBothSurcharges() {
        SlotPlanner.ServiceConfig config = SlotPlanner.withPrograms(
                service("WASH_DRY", 35, 45, List.of("WASHER", "DRYER")),
                List.of(washer("WASH_HEAVY", 45, 8, "10000"), dryer("DRY_LOW", 40, 5, "5000")));

        assertEquals(53, config.processingMinutes());
        assertEquals(45, config.secondaryProcessingMinutes());
        assertEquals(new BigDecimal("55000"), config.price());
        // 5 check-in + 53 wash + 10 transfer + 45 dry + 5 check-out.
        assertEquals(START.plusSeconds(118 * 60), SlotPlanner.buildPlan(config, START).expectedEnd());
    }

    @Test
    void dryOnlyProgramSetsThePrimaryDuration() {
        SlotPlanner.ServiceConfig config = SlotPlanner.withPrograms(
                service("DRY_ONLY", 45, 0, List.of("DRYER")), List.of(dryer("DRY_REFRESH", 15, 1, "0")));

        assertEquals(16, config.processingMinutes());
        assertEquals(new BigDecimal("40000"), config.price());
    }

    @Test
    void noProgramKeepsTheOffering() {
        SlotPlanner.ServiceConfig config = service("IRON_ONLY", 30, 0, List.of("IRON_STATION"));
        assertSame(config, SlotPlanner.withPrograms(config, List.of()));
    }

    @Test
    void devicePayloadUsesSecondsAndTypeSpecificKeys() {
        Map<String, Object> wash = MachineProgramService.toDevicePayload(washer("WASH_QUICK", 20, 5, "0"));
        assertEquals(1200, wash.get("washSeconds"));
        assertEquals(300, wash.get("spinSeconds"));
        assertEquals(12, wash.get("drumRunSeconds"));
        assertEquals(3, wash.get("drumPauseSeconds"));

        Map<String, Object> dry = MachineProgramService.toDevicePayload(dryer("DRY_LOW", 40, 5, "0"));
        assertEquals(2400, dry.get("drySeconds"));
        assertEquals(300, dry.get("coolSeconds"));
        assertEquals(50, dry.get("heatPercent"));
        assertEquals(true, dry.get("uv"));
    }

    private static SlotPlanner.ServiceConfig service(String code, int primary, int secondary,
                                                                 List<String> requirements) {
        return new SlotPlanner.ServiceConfig(code, code, "SELF_SERVICE", "", primary, secondary,
                5, 5, 10, 0, new BigDecimal("40000"), UUID.randomUUID(), "UTC",
                LocalTime.MIN, LocalTime.MAX, requirements, 0);
    }

    private static MachineProgramDto washer(String code, int main, int finish, String surcharge) {
        return new MachineProgramDto(code, "WASHER", code, "", new BigDecimal(surcharge), main, finish,
                main + finish, 12, 3, null, null, false, 0, true);
    }

    private static MachineProgramDto dryer(String code, int main, int finish, String surcharge) {
        return new MachineProgramDto(code, "DRYER", code, "", new BigDecimal(surcharge), main, finish,
                main + finish, null, null, 50, true, false, 0, true);
    }
}
