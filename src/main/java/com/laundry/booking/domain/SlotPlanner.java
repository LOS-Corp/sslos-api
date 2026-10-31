package com.laundry.booking.domain;

import com.laundry.booking.dto.MachineProgramDtos.MachineProgramDto;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.*;
import static com.laundry.booking.dto.BookingConfigDtos.*;

/**
 * Pure booking time arithmetic (BR-07, BR-09, BR-26, BR-30): the phases and resource intervals of a
 * service starting at a given minute, opening-hour coverage across midnight, interval overlap and
 * free-resource combinations. No database, no clock, no Spring — every rule here is unit-testable.
 */
public final class SlotPlanner {

    private SlotPlanner() {
    }

    public record ServiceConfig(
            String code,
            String name,
            String mode,
            String description,
            int processingMinutes,
            int secondaryProcessingMinutes,
            int checkInMinutes,
            int checkoutMinutes,
            int transferMinutes,
            int pickupDeadlineHours,
            BigDecimal price,
            UUID branchId,
            String timezone,
            LocalTime opensAt,
            LocalTime closesAt,
            List<String> requirements,
            int bufferMinutes
    ) {}

    public record Resource(String type, UUID id, String code, String name, String location, String status) {
        public Resource(String type, UUID id) {
            this(type, id, null, null, null, null);
        }
    }

    public record AllocationPlan(
            String type,
            String phase,
            Instant from,
            Instant until
    ) {}

    public record SlotPlan(
            Instant start,
            Instant checkInEnd,
            Instant primaryEnd,
            Instant transferEnd,
            Instant secondaryEnd,
            Instant expectedEnd,
            Instant resourceEnd,
            List<SlotPhaseDto> timeline,
            List<AllocationPlan> allocationPlans
    ) {}

    public record ScheduleHours(LocalTime opensAt, LocalTime closesAt) {}

    public record OccupiedInterval(Instant start, Instant end) {}

    /** {@code primary} plus the first free companion for every other requirement, or null. */
    public static List<Resource> freeCombination(Resource primary,
                                                  SlotPlan plan,
                                                  List<String> requirements,
                                                  Map<String, List<Resource>> resourcesByType,
                                                  Map<UUID, List<OccupiedInterval>> occupied) {
        if (!isFree(primary, plan.allocationPlans().getFirst(), occupied)) {
            return null;
        }
        List<Resource> picked = new ArrayList<>(List.of(primary));
        for (int i = 1; i < requirements.size(); i++) {
            AllocationPlan allocation = plan.allocationPlans().get(i);
            Resource companion = resourcesByType.getOrDefault(requirements.get(i), List.of()).stream()
                    .filter(resource -> !picked.contains(resource) && isFree(resource, allocation, occupied))
                    .findFirst()
                    .orElse(null);
            if (companion == null) {
                return null;
            }
            picked.add(companion);
        }
        return picked;
    }

    public static boolean isCombinationFree(List<Resource> resources,
                                             SlotPlan plan,
                                             Map<UUID, List<OccupiedInterval>> occupied) {
        for (int i = 0; i < resources.size(); i++) {
            if (!isFree(resources.get(i), plan.allocationPlans().get(i), occupied)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isFree(Resource resource, AllocationPlan allocation, Map<UUID, List<OccupiedInterval>> occupied) {
        return occupied.getOrDefault(resource.id(), List.of()).stream()
                .noneMatch(interval -> intervalsOverlap(allocation.from(), allocation.until(),
                        interval.start(), interval.end()));
    }

    public static Instant ceilToMinute(Instant instant) {
        long seconds = instant.getEpochSecond() + (instant.getNano() > 0 ? 1 : 0);
        return Instant.ofEpochSecond(Math.floorDiv(seconds + 59, 60) * 60);
    }

    /**
     * A machine program replaces the processing time of its machine (first
     * requirement = primary, second = secondary) and adds its surcharge.
     */
    public static ServiceConfig withPrograms(ServiceConfig config, List<MachineProgramDto> programs) {
        if (programs.isEmpty()) {
            return config;
        }
        int primary = config.processingMinutes();
        int secondary = config.secondaryProcessingMinutes();
        BigDecimal price = config.price();
        for (MachineProgramDto program : programs) {
            int index = config.requirements().indexOf(program.machineType());
            if (index == 0) primary = program.totalMinutes();
            else if (index == 1) secondary = program.totalMinutes();
            if (price != null && program.surcharge() != null) price = price.add(program.surcharge());
        }
        return new ServiceConfig(config.code(), config.name(), config.mode(), config.description(),
                primary, secondary, config.checkInMinutes(), config.checkoutMinutes(), config.transferMinutes(),
                config.pickupDeadlineHours(), price, config.branchId(), config.timezone(), config.opensAt(),
                config.closesAt(), config.requirements(), config.bufferMinutes());
    }

    public static boolean hasAvailableResourceCombination(List<AllocationPlan> allocations,
                                                   Map<String, List<Resource>> resourcesByType,
                                                   Map<UUID, List<OccupiedInterval>> occupiedByResource) {
        for (AllocationPlan allocation : allocations) {
            boolean available = resourcesByType.getOrDefault(allocation.type(), List.of()).stream()
                    .anyMatch(resource -> occupiedByResource.getOrDefault(resource.id(), List.of()).stream()
                            .noneMatch(occupied -> intervalsOverlap(allocation.from(), allocation.until(),
                                    occupied.start(), occupied.end())));
            if (!available) return false;
        }
        return true;
    }

    public static List<AvailableStartRange> toStartRanges(List<Instant> availableStarts) {
        if (availableStarts.isEmpty()) return List.of();
        List<AvailableStartRange> result = new ArrayList<>();
        Instant rangeStart = availableStarts.getFirst();
        Instant previous = rangeStart;
        for (int index = 1; index < availableStarts.size(); index++) {
            Instant current = availableStarts.get(index);
            if (!current.equals(previous.plusSeconds(60))) {
                result.add(new AvailableStartRange(rangeStart, previous.plusSeconds(60)));
                rangeStart = current;
            }
            previous = current;
        }
        result.add(new AvailableStartRange(rangeStart, previous.plusSeconds(60)));
        return result;
    }

    public static boolean isCoveredByOpeningHours(Map<DayOfWeek, ScheduleHours> weeklyHours,
                                           ZoneId zone,
                                           Instant start,
                                           Instant end) {
        if (!end.isAfter(start)) {
            return false;
        }
        Instant cursor = start;
        while (cursor.isBefore(end)) {
            LocalDate cursorDate = cursor.atZone(zone).toLocalDate();
            Instant coveredUntil = null;
            for (LocalDate openingDate : List.of(cursorDate.minusDays(1), cursorDate)) {
                ScheduleHours hours = weeklyHours.get(openingDate.getDayOfWeek());
                if (hours == null) {
                    continue;
                }
                Instant opens = openingDate.atTime(hours.opensAt()).atZone(zone).toInstant();
                Instant closes = closingTime(openingDate, hours, zone);
                if (!opens.isAfter(cursor) && closes.isAfter(cursor)
                        && (coveredUntil == null || closes.isAfter(coveredUntil))) {
                    coveredUntil = closes;
                }
            }
            if (coveredUntil == null) {
                return false;
            }
            cursor = coveredUntil;
        }
        return true;
    }

    public static Instant closingTime(LocalDate openingDate, ScheduleHours hours, ZoneId zone) {
        if (hours.closesAt().equals(LocalTime.of(23, 59))) {
            return openingDate.plusDays(1).atStartOfDay(zone).toInstant();
        }
        LocalDateTime closesLocal = openingDate.atTime(hours.closesAt());
        if (!hours.closesAt().isAfter(hours.opensAt())) {
            closesLocal = closesLocal.plusDays(1);
        }
        return closesLocal.atZone(zone).toInstant();
    }

    public static SlotPlan buildPlan(ServiceConfig config, Instant start) {
        Instant checkInEnd = start.plusSeconds(config.checkInMinutes() * 60L);
        Instant primaryEnd = checkInEnd.plusSeconds(config.processingMinutes() * 60L);
        boolean combo = "WASH_DRY".equals(config.code());
        boolean locker = "ASSISTED_LOCKER".equals(config.mode());
        Instant transferEnd = combo ? primaryEnd.plusSeconds(config.transferMinutes() * 60L) : primaryEnd;
        Instant secondaryEnd = combo
                ? transferEnd.plusSeconds(config.secondaryProcessingMinutes() * 60L)
                : primaryEnd;
        Instant expectedEnd = secondaryEnd.plusSeconds(config.checkoutMinutes() * 60L);
        Instant resourceEnd = locker
                ? start.plusSeconds(Math.max(config.pickupDeadlineHours(), 1) * 3600L)
                : expectedEnd;

        List<SlotPhaseDto> timeline = new ArrayList<>();
        String checkInResource = config.requirements().getFirst();
        timeline.add(new SlotPhaseDto("CHECK_IN", checkInResource, start, checkInEnd));
        if (combo) {
            timeline.add(new SlotPhaseDto("WASHING", "WASHER", checkInEnd, primaryEnd));
            timeline.add(new SlotPhaseDto("TRANSFER", "WASHER+DRYER", primaryEnd, transferEnd));
            timeline.add(new SlotPhaseDto("DRYING", "DRYER", transferEnd, secondaryEnd));
            timeline.add(new SlotPhaseDto("CHECK_OUT", "DRYER", secondaryEnd, expectedEnd));
        } else if (locker) {
            timeline.add(new SlotPhaseDto("PROCESSING", "STAFF", checkInEnd, primaryEnd));
            timeline.add(new SlotPhaseDto("PICKUP", "LOCKER", primaryEnd, expectedEnd));
        } else {
            String type = config.requirements().getFirst();
            String phase = switch (type) {
                case "WASHER" -> "WASHING";
                case "DRYER" -> "DRYING";
                default -> "IRONING";
            };
            timeline.add(new SlotPhaseDto(phase, type, checkInEnd, primaryEnd));
            timeline.add(new SlotPhaseDto("CHECK_OUT", type, primaryEnd, expectedEnd));
        }

        // The turnover buffer (cleaning / unloading) keeps each resource
        // blocked a little longer, so the next booking starts after it.
        long buffer = Math.max(0, config.bufferMinutes()) * 60L;
        List<AllocationPlan> allocations = new ArrayList<>();
        for (String type : config.requirements()) {
            if (combo && "WASHER".equals(type)) {
                allocations.add(new AllocationPlan(type, "WASHING", start, transferEnd.plusSeconds(buffer)));
            } else if (combo && "DRYER".equals(type)) {
                allocations.add(new AllocationPlan(type, "DRYING", transferEnd, expectedEnd.plusSeconds(buffer)));
            } else {
                allocations.add(new AllocationPlan(type, type, start,
                        (locker ? resourceEnd : expectedEnd).plusSeconds(buffer)));
            }
        }
        return new SlotPlan(start, checkInEnd, primaryEnd, transferEnd, secondaryEnd,
                expectedEnd, resourceEnd, timeline, allocations);
    }

    public static int offsetMinutes(Instant instant) {
        return Math.toIntExact(instant.getEpochSecond() / 60);
    }

    public static boolean intervalsOverlap(Instant requestedStart,
                                    Instant requestedEnd,
                                    Instant existingStart,
                                    Instant existingEnd) {
        return existingStart.isBefore(requestedEnd) && existingEnd.isAfter(requestedStart);
    }

    public static boolean isMinuteAligned(Instant instant) {
        return instant.getNano() == 0 && Math.floorMod(instant.getEpochSecond(), 60) == 0;
    }
}
