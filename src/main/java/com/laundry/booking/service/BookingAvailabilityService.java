package com.laundry.booking.service;

import com.laundry.shared.error.ErrorCode;

import com.laundry.shared.error.BusinessException;

import com.laundry.booking.dto.BookingDtos;
import com.laundry.branch.entity.Branch;
import com.laundry.branch.entity.BranchStatus;
import com.laundry.branch.repository.BranchRepository;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.*;
import static com.laundry.booking.dto.BookingConfigDtos.*;

import com.laundry.booking.domain.SlotPlanner.*;
import static com.laundry.booking.domain.SlotPlanner.*;

/**
 * Customer-facing availability (UC-16): service catalog, bookable branches, the free-time timeline,
 * recommendations and the machine search. Read-only snapshots — the hold re-checks under locks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingAvailabilityService {
    private final JdbcTemplate jdbc;
    private final BranchRepository branchRepository;
    private final MachineRepository machineRepository;
    private final BranchBookingReadinessService readinessService;
    private final BookingResourceQueries queries;
    private final com.laundry.config.BookingProperties props;

    /** Longest start-time window one machine search may cover (allows overnight / 24/7 windows). */
    static final java.time.Duration MAX_SEARCH_WINDOW = java.time.Duration.ofHours(48);



    @Transactional(readOnly = true)
    public List<ServiceDto> listServices() {
        // Map rows first, resolve requirements afterwards: a nested query inside the
        // RowMapper runs while the outer ResultSet is still open on the same connection.
        List<ServiceDto> services = jdbc.query("""
                SELECT code, name, service_mode, description, processing_minutes,
                       secondary_processing_minutes, check_in_minutes, transfer_minutes,
                       checkout_minutes, pickup_deadline_hours,
                       (SELECT MIN(price) FROM branch_service_offerings
                        WHERE service_code = booking_service_definitions.code AND enabled = TRUE) starting_price
                FROM booking_service_definitions
                WHERE active = TRUE
                ORDER BY CASE service_mode WHEN 'SELF_SERVICE' THEN 0 ELSE 1 END, name
                """, (rs, row) -> {
            String code = rs.getString("code");
            return new ServiceDto(
                    code,
                    rs.getString("name"),
                    rs.getString("service_mode"),
                    rs.getString("description"),
                    rs.getInt("processing_minutes"),
                    rs.getInt("secondary_processing_minutes"),
                    rs.getInt("check_in_minutes"),
                    rs.getInt("transfer_minutes"),
                    rs.getInt("checkout_minutes"),
                    rs.getInt("pickup_deadline_hours"),
                    rs.getBigDecimal("starting_price"),
                    List.<String>of());
        });
        return services.stream()
                .map(s -> new ServiceDto(
                        s.code(), s.name(), s.mode(), s.description(),
                        s.processingMinutes(), s.secondaryProcessingMinutes(),
                        s.checkInMinutes(), s.transferMinutes(), s.checkoutMinutes(),
                        s.pickupDeadlineHours(), s.startingPrice(),
                        queries.requirementsFor(s.code())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BranchServiceDto> listBranches(String serviceCode) {
        ServiceConfig config = queries.serviceDefinition(serviceCode);
        List<Branch> branches = branchRepository.findByStatusOrderByNameAsc(BranchStatus.ACTIVE);
        List<BranchServiceDto> result = new ArrayList<>();
        for (Branch branch : branches) {
            ServiceConfig offered = queries.customerOffering(branch.getId(), serviceCode, null, null);
            if (offered == null || !readinessService.isCustomerEligible(branch.getId(), serviceCode)) {
                continue;
            }
            List<String> resources = new ArrayList<>();
            for (String requirement : config.requirements()) {
                resources.add(requirement);
            }
            result.add(new BranchServiceDto(
                    branch.getId(), branch.getName(), branch.getAddress(), branch.getPhone(),
                    offered.timezone(), offered.price(), offered.processingMinutes(),
                    offered.secondaryProcessingMinutes(), offered.checkInMinutes(),
                    offered.transferMinutes(), offered.checkoutMinutes(),
                    queries.openingHours(branch.getId()), resources));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public TimelineAvailabilityResponse timelineAvailability(UUID branchId, String serviceCode, LocalDate date,
                                                             String washProgram, String dryProgram) {
        ServiceConfig config = queries.customerOffering(branchId, serviceCode, washProgram, dryProgram);
        if (config == null) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "This service is not configured for the selected branch");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(config.timezone());
        } catch (RuntimeException e) {
            throw BusinessException.of(ErrorCode.CONFIG_CONFLICT, "Branch timezone is invalid");
        }
        LocalDate today = LocalDate.now(zone);
        // BR-05: a booking starts at most 48 h ahead, so only today and the days that horizon reaches are shown.
        LocalDate lastBookableDay = Instant.now().plus(props.maxAdvance()).atZone(zone).toLocalDate();
        if (date.isBefore(today) || date.isAfter(lastBookableDay)) {
            throw BusinessException.of(ErrorCode.BEYOND_ADVANCE_HORIZON, "You can book at most 48 hours ahead");
        }

        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        SlotPlan lastStartPlan = buildPlan(config, dayEnd.minusSeconds(60));
        Instant queryEnd = lastStartPlan.allocationPlans().stream()
                .map(AllocationPlan::until)
                .max(Comparator.naturalOrder())
                .orElse(dayEnd);
        Map<DayOfWeek, ScheduleHours> weeklyHours = queries.scheduleHours(branchId);
        List<AvailabilityInterval> operatingIntervals =
                operatingIntervals(weeklyHours, zone, dayStart, dayEnd);
        List<String> requirements = config.requirements();
        Map<String, List<Resource>> resourcesByType = new java.util.LinkedHashMap<>();
        Map<UUID, Resource> resourcesById = new java.util.HashMap<>();
        for (String requirement : requirements) {
            List<Resource> resources = queries.resourcesFor(branchId, requirement);
            resourcesByType.put(requirement, resources);
            resources.forEach(resource -> resourcesById.put(resource.id(), resource));
        }

        Map<UUID, List<OccupiedInterval>> occupiedByResource =
                queries.occupiedIntervals(branchId, dayStart, queryEnd, resourcesById, config.bufferMinutes());
        List<AvailabilityInterval> unavailableIntervals = unavailableIntervals(
                branchId, config, dayStart, dayEnd, occupiedByResource, resourcesById);
        Map<UUID, BookingDtos.TimelineResource> timelineResources = new java.util.LinkedHashMap<>();
        requirements.forEach(requirement -> resourcesByType.getOrDefault(requirement, List.of())
                .forEach(resource -> timelineResources.put(resource.id(),
                        new BookingDtos.TimelineResource(resource.id(), resource.type(), resource.code(),
                                resource.name(), resource.status()))));
        unavailableIntervals.stream()
                .filter(interval -> interval.resourceId() != null)
                .forEach(interval -> timelineResources.putIfAbsent(interval.resourceId(),
                        new BookingDtos.TimelineResource(interval.resourceId(), interval.resourceType(),
                                interval.resourceCode(), interval.resourceCode(), "UNAVAILABLE")));

        SlotPlan templatePlan = buildPlan(config, Instant.EPOCH);
        int expectedDurationMinutes = offsetMinutes(templatePlan.expectedEnd());
        List<TimelinePhaseTemplate> servicePhases = templatePlan.timeline().stream()
                .map(phase -> new TimelinePhaseTemplate(phase.phase(), phase.resourceType(),
                        offsetMinutes(phase.startTime()), offsetMinutes(phase.endTime())))
                .toList();
        List<ResourceOccupancyTemplate> resourceOccupancies = templatePlan.allocationPlans().stream()
                .map(allocation -> new ResourceOccupancyTemplate(allocation.type(), allocation.phase(),
                        offsetMinutes(allocation.from()), offsetMinutes(allocation.until())))
                .toList();
        List<Instant> availableStarts = new ArrayList<>();
        Instant earliest = Instant.now();
        for (Instant start = dayStart; start.isBefore(dayEnd); start = start.plusSeconds(60)) {
            if (start.isBefore(earliest)) {
                continue;
            }
            SlotPlan plan = buildPlan(config, start);
            Instant bookingEnd = "ASSISTED_LOCKER".equals(config.mode())
                    ? plan.checkInEnd() : plan.expectedEnd();
            if (!isCoveredByOpeningHours(weeklyHours, zone, start, bookingEnd)
                    || !hasAvailableResourceCombination(plan.allocationPlans(),
                    resourcesByType, occupiedByResource)) {
                continue;
            }
            availableStarts.add(start);
        }
        return new TimelineAvailabilityResponse(date, zone.getId(), 1, dayStart, dayEnd,
                expectedDurationMinutes, servicePhases, resourceOccupancies,
                List.copyOf(timelineResources.values()),
                operatingIntervals, unavailableIntervals,
                toStartRanges(availableStarts));
    }

    @Transactional(readOnly = true)
    public RecommendationsResponse recommendations(UUID branchId, String serviceCode,
                                                    Instant desiredStartTime, Instant preferredEndTime,
                                                    String washProgram, String dryProgram) {
        if (!isMinuteAligned(desiredStartTime)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Start time must be aligned to a whole minute");
        }
        ServiceConfig config = queries.customerOffering(branchId, serviceCode, washProgram, dryProgram);
        if (config == null) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "This service is not configured for the selected branch");
        }
        SlotPlan plan = buildPlan(config, desiredStartTime);
        boolean preferredEndSatisfied = preferredEndTime == null
                || !plan.expectedEnd().isAfter(preferredEndTime);
        List<RecommendationDto> recommendations = new ArrayList<>();
        if (preferredEndSatisfied && queries.isWithinOpeningHours(config, desiredStartTime, plan)) {
            List<List<Resource>> candidates = new ArrayList<>();
            for (int i = 0; i < config.requirements().size(); i++) {
                AllocationPlan allocation = plan.allocationPlans().get(i);
                candidates.add(queries.resourcesFor(config.branchId(), config.requirements().get(i)).stream()
                        .filter(resource -> !queries.resourceBusy(resource, allocation, null,
                                config.bufferMinutes()))
                        .toList());
            }
            collectRecommendations(config, plan, candidates, 0, new ArrayList<>(), recommendations);
        }
        recommendations.sort(Comparator.comparing(RecommendationDto::price)
                .thenComparing(recommendation -> recommendation.resources().stream()
                        .map(RecommendedResourceDto::resourceCode)
                        .sorted()
                        .reduce("", String::concat)));

        return new RecommendationsResponse(desiredStartTime, plan.expectedEnd(), preferredEndTime,
                preferredEndSatisfied, recommendations);
    }

    /**
     * Smart machine search. For every compatible machine, finds the earliest
     * minute in [{@code from}, {@code to}] at which the whole service (plus the
     * turnover buffer) fits: machine bookable (online, commissioned, not in
     * ERROR / OUT_OF_SERVICE), no live booking, active hold, legacy reservation
     * or running session overlapping, and inside branch opening hours (which
     * may span midnight or be 24/7). Combo services pair the machine with the
     * first free companion resource. Results: earliestStart ASC, then price.
     *
     * <p>This is a read-only snapshot — {@link #createHold} re-checks the chosen
     * start and resources under row locks before reserving anything.
     */
    @Transactional(readOnly = true)
    public MachineSearchResponse machineSearch(UUID branchId, String serviceCode, Instant from, Instant to,
                                               String washProgram, String dryProgram, BigDecimal declaredLoadKg) {
        ServiceConfig config = queries.customerOffering(branchId, serviceCode, washProgram, dryProgram);
        if (config == null) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "This service is not configured for the selected branch");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(config.timezone());
        } catch (RuntimeException e) {
            throw BusinessException.of(ErrorCode.CONFIG_CONFLICT, "Branch timezone is invalid");
        }
        if (to.isBefore(from)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "The search window must end after it starts");
        }
        if (java.time.Duration.between(from, to).compareTo(MAX_SEARCH_WINDOW) > 0) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "The search window can cover at most 48 hours");
        }
        Instant now = Instant.now();
        if (from.isAfter(now.plus(props.maxAdvance()))) {
            throw BusinessException.of(ErrorCode.BEYOND_ADVANCE_HORIZON, "You can book at most 48 hours ahead");
        }
        Instant firstStart = ceilToMinute(from.isAfter(now) ? from : now);
        Instant lastStart = Instant.ofEpochSecond(Math.floorDiv(to.getEpochSecond(), 60) * 60);
        if (lastStart.isBefore(firstStart)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "The selected time window has already passed");
        }

        List<String> requirements = config.requirements();
        Map<String, List<Resource>> resourcesByType = new java.util.LinkedHashMap<>();
        Map<UUID, Resource> resourcesById = new java.util.HashMap<>();
        for (String requirement : requirements) {
            List<Resource> resources = queries.resourcesFor(branchId, requirement);
            resourcesByType.put(requirement, resources);
            resources.forEach(resource -> resourcesById.put(resource.id(), resource));
        }
        Map<UUID, Machine> machines = new java.util.HashMap<>();
        machineRepository.findByBranchId(branchId).forEach(machine -> machines.put(machine.getId(), machine));

        // BR-26 / SC-42: machines smaller than the declared load are never offered.
        List<UnavailableMachineDto> tooSmall = new ArrayList<>();
        if (declaredLoadKg != null) {
            resourcesByType.replaceAll((type, list) -> list.stream().filter(resource -> {
                Machine machine = machines.get(resource.id());
                boolean fits = machine == null || machine.getCapacityKg() == null
                        || machine.getCapacityKg().compareTo(declaredLoadKg) >= 0;
                if (!fits && type.equals(requirements.getFirst())) {
                    tooSmall.add(unavailableDto(resource, machine, "TOO_SMALL"));
                }
                return fits;
            }).toList());
        }

        Instant queryEnd = buildPlan(config, lastStart).allocationPlans().stream()
                .map(AllocationPlan::until)
                .max(Comparator.naturalOrder())
                .orElse(lastStart);
        Map<UUID, List<OccupiedInterval>> occupied = queries.occupiedIntervals(
                branchId, firstStart, queryEnd, resourcesById, config.bufferMinutes());

        // Every whole minute in the window whose service fits the opening hours.
        boolean locker = "ASSISTED_LOCKER".equals(config.mode());
        Map<DayOfWeek, ScheduleHours> weeklyHours = queries.scheduleHours(branchId);
        List<SlotPlan> plans = new ArrayList<>();
        for (Instant start = firstStart; !start.isAfter(lastStart); start = start.plusSeconds(60)) {
            SlotPlan plan = buildPlan(config, start);
            Instant bookingEnd = locker ? plan.checkInEnd() : plan.expectedEnd();
            if (isCoveredByOpeningHours(weeklyHours, zone, start, bookingEnd)) {
                plans.add(plan);
            }
        }

        String primaryType = requirements.getFirst();
        List<MachineSearchOptionDto> options = new ArrayList<>();
        List<UnavailableMachineDto> unavailable = new ArrayList<>(tooSmall);
        for (Resource primary : resourcesByType.getOrDefault(primaryType, List.of())) {
            List<Resource> combo = null;
            SlotPlan earliest = null;
            for (SlotPlan plan : plans) {
                combo = freeCombination(primary, plan, requirements, resourcesByType, occupied);
                if (combo != null) {
                    earliest = plan;
                    break;
                }
            }
            if (combo == null) {
                unavailable.add(unavailableDto(primary, machines.get(primary.id()), "FULLY_BOOKED"));
                continue;
            }
            List<Resource> chosen = combo;
            List<Instant> starts = plans.stream()
                    .filter(plan -> isCombinationFree(chosen, plan, occupied))
                    .map(SlotPlan::start)
                    .toList();
            options.add(searchOption(config, chosen, earliest, starts, machines));
        }

        // Compatible machines that cannot be booked at all right now.
        if ("WASHER".equals(primaryType) || "DRYER".equals(primaryType)) {
            MachineType machineType = MachineType.valueOf(primaryType);
            machines.values().stream()
                    .filter(machine -> machine.getType() == machineType && !queries.isOperationallyUsable(machine))
                    .forEach(machine -> unavailable.add(new UnavailableMachineDto(machine.getId(), primaryType,
                            machine.getMachineCode(), displayNameOf(machine), machine.getCapacityKg(),
                            unavailableReason(machine))));
        } else {
            jdbc.query("""
                    SELECT id, resource_code, display_name FROM branch_service_resources
                    WHERE branch_id = ? AND resource_type = ? AND enabled = FALSE
                    """, rs -> {
                unavailable.add(new UnavailableMachineDto(rs.getObject("id", UUID.class), primaryType,
                        rs.getString("resource_code"), rs.getString("display_name"), null, "DISABLED"));
            }, branchId, primaryType);
        }

        options.sort(Comparator.comparing(MachineSearchOptionDto::earliestStart)
                .thenComparing(MachineSearchOptionDto::price)
                .thenComparing(option -> option.resources().getFirst().resourceCode()));
        unavailable.sort(Comparator.comparing(UnavailableMachineDto::resourceCode));
        int durationMinutes = offsetMinutes(buildPlan(config, Instant.EPOCH).expectedEnd());
        return new MachineSearchResponse(branchId, config.code(), zone.getId(), from, to, firstStart,
                durationMinutes, config.bufferMinutes(), options, unavailable);
    }

    private MachineSearchOptionDto searchOption(ServiceConfig config,
                                                List<Resource> resources,
                                                SlotPlan plan,
                                                List<Instant> starts,
                                                Map<UUID, Machine> machines) {
        List<MachineSearchResourceDto> resourceDtos = resources.stream()
                .map(resource -> {
                    Machine machine = machines.get(resource.id());
                    return new MachineSearchResourceDto(resource.id(), resource.type(), resource.code(),
                            resource.name(), resource.location(),
                            machine == null ? null : machine.getCapacityKg(), resource.status());
                })
                .toList();
        String optionId = resources.stream()
                .map(resource -> resource.id().toString())
                .reduce((left, right) -> left + ":" + right)
                .orElse("");
        Instant reservedUntil = plan.allocationPlans().stream()
                .map(AllocationPlan::until)
                .max(Comparator.naturalOrder())
                .orElse(plan.expectedEnd());
        int totalMinutes = Math.toIntExact((plan.expectedEnd().getEpochSecond() - plan.start().getEpochSecond()) / 60);
        return new MachineSearchOptionDto(optionId, resourceDtos, plan.start(), plan.expectedEnd(),
                reservedUntil, config.price(), "VND", totalMinutes, toStartRanges(starts), plan.timeline());
    }

    private UnavailableMachineDto unavailableDto(Resource resource, Machine machine, String reason) {
        return new UnavailableMachineDto(resource.id(), resource.type(), resource.code(), resource.name(),
                machine == null ? null : machine.getCapacityKg(), reason);
    }

    private static String unavailableReason(Machine machine) {
        if (!machine.isCommissioned()) return "NOT_COMMISSIONED";
        if (!machine.isOnline()) return "OFFLINE";
        if (machine.getStatus() == MachineStatus.ERROR) return "ERROR";
        if (machine.getStatus() == MachineStatus.OUT_OF_SERVICE) return "OUT_OF_SERVICE";
        return "OFFLINE";
    }

    private static String displayNameOf(Machine machine) {
        return machine.getDisplayName() == null || machine.getDisplayName().isBlank()
                ? machine.getMachineCode() : machine.getDisplayName();
    }

    private List<AvailabilityInterval> operatingIntervals(Map<DayOfWeek, ScheduleHours> weeklyHours,
                                                          ZoneId zone,
                                                          Instant dayStart,
                                                          Instant dayEnd) {
        List<Instant[]> intervals = new ArrayList<>();
        LocalDate firstDate = dayStart.atZone(zone).toLocalDate().minusDays(1);
        LocalDate lastDate = dayEnd.atZone(zone).toLocalDate();
        for (LocalDate openingDate = firstDate; !openingDate.isAfter(lastDate);
             openingDate = openingDate.plusDays(1)) {
            ScheduleHours hours = weeklyHours.get(openingDate.getDayOfWeek());
            if (hours == null) continue;
            Instant opens = openingDate.atTime(hours.opensAt()).atZone(zone).toInstant();
            Instant closes = closingTime(openingDate, hours, zone);
            Instant visibleStart = opens.isAfter(dayStart) ? opens : dayStart;
            Instant visibleEnd = closes.isBefore(dayEnd) ? closes : dayEnd;
            if (visibleEnd.isAfter(visibleStart)) {
                intervals.add(new Instant[] {visibleStart, visibleEnd});
            }
        }
        intervals.sort(Comparator.comparing(interval -> interval[0]));
        List<AvailabilityInterval> result = new ArrayList<>();
        Instant start = null;
        Instant end = null;
        for (Instant[] interval : intervals) {
            if (end != null && !interval[0].isAfter(end)) {
                if (interval[1].isAfter(end)) end = interval[1];
            } else {
                if (start != null) {
                    result.add(new AvailabilityInterval(start, end, "OPERATING", "Chi nhánh mở cửa",
                            null, null, null));
                }
                start = interval[0];
                end = interval[1];
            }
        }
        if (start != null) {
            result.add(new AvailabilityInterval(start, end, "OPERATING", "Chi nhánh mở cửa",
                    null, null, null));
        }
        return result;
    }

    private List<AvailabilityInterval> unavailableIntervals(UUID branchId,
                                                            ServiceConfig config,
                                                            Instant dayStart,
                                                            Instant dayEnd,
                                                            Map<UUID, List<OccupiedInterval>> occupied,
                                                            Map<UUID, Resource> resourcesById) {
        List<AvailabilityInterval> result = new ArrayList<>();
        Set<String> requiredTypes = Set.copyOf(config.requirements());
        for (Machine machine : machineRepository.findByBranchId(branchId)) {
            String type = machine.getType().name();
            // A locker cabinet is not booked itself; its compartments are (filtered in resourcesFor).
            if (machine.getType() == MachineType.LOCKER) continue;
            if (requiredTypes.contains(type) && !queries.isOperationallyUsable(machine)) {
                String code = machine.getMachineCode();
                result.add(new AvailabilityInterval(dayStart, dayEnd, "UNAVAILABLE",
                        "Thiết bị không khả dụng: " + code, type, code, machine.getId()));
            }
        }
        for (String type : requiredTypes) {
            if ("WASHER".equals(type) || "DRYER".equals(type)) continue;
            jdbc.query("""
                    SELECT id, resource_code FROM branch_service_resources
                    WHERE branch_id = ? AND resource_type = ? AND enabled = FALSE
                    """, rs -> {
                UUID resourceId = rs.getObject("id", UUID.class);
                String code = rs.getString("resource_code");
                result.add(new AvailabilityInterval(dayStart, dayEnd, "UNAVAILABLE",
                        "Tài nguyên không khả dụng: " + code, type, code, resourceId));
            }, branchId, type);
        }

        occupied.forEach((resourceId, intervals) -> {
            Resource resource = resourcesById.get(resourceId);
            if (resource == null) return;
            for (OccupiedInterval interval : intervals) {
                if (interval.end().isAfter(dayStart) && interval.start().isBefore(dayEnd)) {
                    result.add(new AvailabilityInterval(interval.start(), interval.end(), "RESERVATION",
                            "Đã đặt: " + resource.code(), resource.type(), resource.code(), resource.id()));
                }
            }
        });
        result.sort(Comparator.comparing(AvailabilityInterval::startTime)
                .thenComparing(AvailabilityInterval::resourceCode,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    private void collectRecommendations(ServiceConfig config,
                                        SlotPlan plan,
                                        List<List<Resource>> candidates,
                                        int index,
                                        List<Resource> selected,
                                        List<RecommendationDto> result) {
        if (index == candidates.size()) {
            List<RecommendedResourceDto> resources = selected.stream()
                    .map(resource -> new RecommendedResourceDto(resource.id(), resource.type(),
                            resource.code(), resource.name(), resource.location(),
                            "AVAILABLE_AT_SELECTED_TIME"))
                    .toList();
            String recommendationId = selected.stream()
                    .map(resource -> resource.id().toString())
                    .reduce((left, right) -> left + ":" + right)
                    .orElse("");
            Instant occupiedUntil = plan.allocationPlans().stream()
                    .map(AllocationPlan::until)
                    .max(Comparator.naturalOrder())
                    .orElse(plan.expectedEnd());
            result.add(new RecommendationDto(recommendationId, plan.start(), occupiedUntil,
                    plan.expectedEnd(), config.price(), "VND",
                    Math.toIntExact((plan.expectedEnd().toEpochMilli() - plan.start().toEpochMilli()) / 60_000),
                    resources, plan.timeline()));
            return;
        }
        for (Resource resource : candidates.get(index)) {
            if (result.size() >= 20) return;
            selected.add(resource);
            collectRecommendations(config, plan, candidates, index + 1, selected, result);
            selected.removeLast();
        }
    }
}
