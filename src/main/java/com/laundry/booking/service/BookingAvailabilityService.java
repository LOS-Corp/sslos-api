package com.laundry.booking.service;

import com.laundry.store.entity.StoreSettings;
import com.laundry.store.service.StoreService;

import com.laundry.shared.error.ErrorCode;

import com.laundry.shared.error.BusinessException;

import com.laundry.booking.dto.BookingDtos;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

import com.laundry.booking.domain.SlotPlanner.*;
import static com.laundry.booking.domain.SlotPlanner.*;

/**
 * Customer-facing availability (UC-16): service catalog, what the store offers, the free-time timeline,
 * recommendations and the machine search. Read-only snapshots — the hold re-checks under locks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingAvailabilityService {
    private final JdbcTemplate jdbc;
    private final StoreService store;
    private final MachineRepository machineRepository;
    private final StoreBookingReadinessService readinessService;
    private final BookingResourceQueries queries;
    private final com.laundry.config.BookingProperties props;

    /** Longest start-time window one machine search may cover (allows overnight / 24/7 windows). */
    static final java.time.Duration MAX_SEARCH_WINDOW = java.time.Duration.ofHours(48);



    @Transactional(readOnly = true)
    public List<ServiceDto> listServices() {
        // Map rows first, resolve requirements afterwards: a nested query inside the
        // RowMapper runs while the outer ResultSet is still open on the same connection.
        List<ServiceDto> services = jdbc.query("""
                SELECT d.code, d.name, d.service_mode, d.description, d.processing_minutes,
                       d.secondary_processing_minutes, d.pickup_deadline_hours,
                       (SELECT MIN(price) FROM service_offerings
                        WHERE service_code = d.code AND enabled = TRUE) starting_price,
                """ + BookingResourceQueries.STAGE_MINUTES_SQL + """

                FROM booking_service_definitions d CROSS JOIN store_settings s
                WHERE d.active = TRUE
                ORDER BY CASE d.service_mode WHEN 'SELF_SERVICE' THEN 0 ELSE 1 END, d.name
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
                .map(s -> {
                    List<String> requirements = queries.requirementsFor(s.code());
                    // V68: washers and dryers are priced by their profiles, from the cheapest one.
                    boolean byMachine = BookingResourceQueries.pricedByMachine(s.mode(), requirements);
                    BigDecimal startingPrice = byMachine ? queries.startingPrice(requirements) : s.startingPrice();
                    // V71: machine services have no duration of their own: the longest machine cycle per stage.
                    Integer primary = byMachine ? queries.longestCycle(requirements.get(0)) : null;
                    Integer secondary = byMachine && requirements.size() > 1 ? queries.longestCycle(requirements.get(1)) : null;
                    return new ServiceDto(
                            s.code(), s.name(), s.mode(), s.description(),
                            primary != null ? primary : s.processingMinutes(),
                            secondary != null ? secondary : s.secondaryProcessingMinutes(),
                            s.checkInMinutes(), s.transferMinutes(), s.checkoutMinutes(),
                            s.pickupDeadlineHours(), startingPrice, requirements);
                })
                .toList();
    }

    /** Services customers can book at the store right now, with price, durations and opening hours. */
    @Transactional
    public List<StoreOfferingDto> listOfferings() {
        var readiness = readinessService.evaluate();
        if (!readiness.ready()) {
            return List.of();
        }
        StoreSettings settings = store.settings();
        var hours = queries.openingHours();
        List<StoreOfferingDto> result = new ArrayList<>();
        for (var service : readiness.services()) {
            if (!service.enabled() || !service.ready()) {
                continue;
            }
            ServiceConfig offered = queries.customerOffering(service.code(), null);
            if (offered == null) {
                continue;
            }
            result.add(new StoreOfferingDto(service.code(), settings.getName(), settings.getAddress(),
                    settings.getPhone(), offered.timezone(), offered.price(), offered.processingMinutes(),
                    offered.secondaryProcessingMinutes(), offered.checkInMinutes(),
                    offered.transferMinutes(), offered.checkoutMinutes(), hours,
                    List.copyOf(offered.requirements()), queries.pricePerKg(service.code()),
                    maxWasherCapacityKg(),
                    BookingResourceQueries.pricedByMachine(offered.mode(), offered.requirements())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public TimelineAvailabilityResponse timelineAvailability(String serviceCode, LocalDate date, BigDecimal loadKg) {
        ServiceConfig config = queries.customerOffering(serviceCode, loadKg);
        if (config == null) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "This service is not offered at the store");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(config.timezone());
        } catch (RuntimeException e) {
            throw BusinessException.of(ErrorCode.CONFIG_CONFLICT, "Store timezone is invalid");
        }
        LocalDate today = LocalDate.now(zone);
        // BR-05: a booking starts at most 48 h ahead, so only today and the days that horizon reaches are shown.
        LocalDate lastBookableDay = Instant.now().plus(props.maxAdvance()).atZone(zone).toLocalDate();
        if (date.isBefore(today) || date.isAfter(lastBookableDay)) {
            throw BusinessException.of(ErrorCode.BEYOND_ADVANCE_HORIZON, "You can book at most 48 hours ahead");
        }

        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        Map<DayOfWeek, ScheduleHours> weeklyHours = queries.scheduleHours();
        List<AvailabilityInterval> operatingIntervals =
                operatingIntervals(weeklyHours, zone, dayStart, dayEnd);
        List<String> requirements = config.requirements();
        List<Machine> machinesInStore = machineRepository.findAll();
        Map<String, List<Resource>> resourcesByType = new java.util.LinkedHashMap<>(
                queries.resourcesByType(requirements, machinesInStore));
        dropTooSmall(resourcesByType, machinesInStore, loadKg);
        Map<UUID, Resource> resourcesById = byId(resourcesByType);
        List<Variant> variants = variants(config, resourcesByType);
        Instant queryEnd = latestAllocationEnd(variants, dayEnd.minusSeconds(60));
        if (queryEnd.isBefore(dayEnd)) queryEnd = dayEnd;

        Map<UUID, List<OccupiedInterval>> occupiedByResource =
                queries.occupiedIntervals(dayStart, queryEnd, resourcesById, config.bufferMinutes());
        List<AvailabilityInterval> unavailableIntervals = unavailableIntervals(config, dayStart, dayEnd, occupiedByResource, resourcesById);
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
            // V71: free when some machine (of any cycle) can take the whole service from this minute.
            for (Variant variant : variants) {
                SlotPlan plan = buildPlan(variant.config(), start);
                Instant bookingEnd = "ASSISTED_LOCKER".equals(config.mode())
                        ? plan.checkInEnd() : plan.expectedEnd();
                if (isCoveredByOpeningHours(weeklyHours, zone, start, bookingEnd)
                        && hasAvailableResourceCombination(plan.allocationPlans(),
                        variant.resourcesByType(), occupiedByResource)) {
                    availableStarts.add(start);
                    break;
                }
            }
        }
        return new TimelineAvailabilityResponse(date, zone.getId(), 1, dayStart, dayEnd,
                expectedDurationMinutes, servicePhases, resourceOccupancies,
                List.copyOf(timelineResources.values()),
                operatingIntervals, unavailableIntervals,
                toStartRanges(availableStarts));
    }

    @Transactional(readOnly = true)
    public RecommendationsResponse recommendations(String serviceCode,
                                                    Instant desiredStartTime, Instant preferredEndTime,
                                                    BigDecimal loadKg) {
        if (!isMinuteAligned(desiredStartTime)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Start time must be aligned to a whole minute");
        }
        ServiceConfig config = queries.customerOffering(serviceCode, loadKg);
        if (config == null) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "This service is not offered at the store");
        }
        SlotPlan plan = buildPlan(config, desiredStartTime);
        List<RecommendationDto> recommendations = new ArrayList<>();
        List<Machine> machinesInStore = machineRepository.findAll();
        Map<String, List<Resource>> resourcesByType = new java.util.LinkedHashMap<>(
                queries.resourcesByType(config.requirements(), machinesInStore));
        dropTooSmall(resourcesByType, machinesInStore, loadKg);
        Map<UUID, BigDecimal> capacities = new java.util.HashMap<>();
        machinesInStore.forEach(machine -> {
            if (machine.getCapacityKg() != null) capacities.put(machine.getId(), machine.getCapacityKg());
        });
        // V71: every machine is planned with its own cycle, so a faster machine may end before the preferred time.
        boolean preferredEndSatisfied = false;
        for (Variant variant : variants(config, resourcesByType)) {
            SlotPlan variantPlan = buildPlan(variant.config(), desiredStartTime);
            boolean endsInTime = preferredEndTime == null || !variantPlan.expectedEnd().isAfter(preferredEndTime);
            preferredEndSatisfied |= endsInTime;
            if (!endsInTime || !queries.isWithinOpeningHours(variant.config(), desiredStartTime, variantPlan)) {
                continue;
            }
            List<List<Resource>> candidates = new ArrayList<>();
            for (int i = 0; i < config.requirements().size(); i++) {
                AllocationPlan allocation = variantPlan.allocationPlans().get(i);
                List<Resource> ofType = variant.resourcesByType().getOrDefault(config.requirements().get(i), List.of());
                Set<UUID> busy = queries.busyResources(ofType, allocation, config.bufferMinutes());
                candidates.add(ofType.stream().filter(resource -> !busy.contains(resource.id())).toList());
            }
            collectRecommendations(variant.config(), variantPlan, candidates, 0, new ArrayList<>(), recommendations,
                    loadKg, capacities);
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
     * or running session overlapping, and inside the store opening hours (which
     * may span midnight or be 24/7). Combo services pair the machine with the
     * first free companion resource. Results: earliestStart ASC, then price.
     *
     * <p>This is a read-only snapshot — {@link #createHold} re-checks the chosen
     * start and resources under row locks before reserving anything.
     */
    @Transactional(readOnly = true)
    public MachineSearchResponse machineSearch(String serviceCode, Instant from, Instant to,
                                               BigDecimal declaredLoadKg) {
        ServiceConfig config = queries.customerOffering(serviceCode, declaredLoadKg);
        if (config == null) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "This service is not offered at the store");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(config.timezone());
        } catch (RuntimeException e) {
            throw BusinessException.of(ErrorCode.CONFIG_CONFLICT, "Store timezone is invalid");
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
        List<Machine> machinesInStore = machineRepository.findAll();
        Map<String, List<Resource>> resourcesByType = new java.util.LinkedHashMap<>(
                queries.resourcesByType(requirements, machinesInStore));
        Map<UUID, Resource> resourcesById = byId(resourcesByType);
        Map<UUID, Machine> machines = new java.util.HashMap<>();
        machinesInStore.forEach(machine -> machines.put(machine.getId(), machine));

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

        List<Variant> variants = variants(config, resourcesByType);
        Instant queryEnd = latestAllocationEnd(variants, lastStart);
        Map<UUID, List<OccupiedInterval>> occupied = queries.occupiedIntervals(firstStart, queryEnd, resourcesById, config.bufferMinutes());

        // Per variant (V71: one cycle per stage), every whole minute in the window whose service fits the opening hours.
        boolean locker = "ASSISTED_LOCKER".equals(config.mode());
        Map<DayOfWeek, ScheduleHours> weeklyHours = queries.scheduleHours();
        Map<Variant, List<SlotPlan>> plansByVariant = new java.util.IdentityHashMap<>();
        for (Variant variant : variants) {
            List<SlotPlan> plans = new ArrayList<>();
            for (Instant start = firstStart; !start.isAfter(lastStart); start = start.plusSeconds(60)) {
                SlotPlan plan = buildPlan(variant.config(), start);
                Instant bookingEnd = locker ? plan.checkInEnd() : plan.expectedEnd();
                if (isCoveredByOpeningHours(weeklyHours, zone, start, bookingEnd)) {
                    plans.add(plan);
                }
            }
            plansByVariant.put(variant, plans);
        }

        String primaryType = requirements.getFirst();
        List<MachineSearchOptionDto> options = new ArrayList<>();
        List<UnavailableMachineDto> unavailable = new ArrayList<>(tooSmall);
        for (Resource primary : resourcesByType.getOrDefault(primaryType, List.of())) {
            // The earliest free start of this machine over the variants it belongs to (its own cycle, and each
            // companion cycle for two-machine services).
            Variant best = null;
            List<Resource> combo = null;
            SlotPlan earliest = null;
            for (Variant variant : variants) {
                if (!variant.resourcesByType().getOrDefault(primaryType, List.of()).contains(primary)) continue;
                for (SlotPlan plan : plansByVariant.get(variant)) {
                    if (earliest != null && !plan.start().isBefore(earliest.start())) break;
                    List<Resource> found = freeCombination(primary, plan, requirements, variant.resourcesByType(), occupied);
                    if (found != null) {
                        best = variant;
                        combo = found;
                        earliest = plan;
                        break;
                    }
                }
            }
            if (combo == null) {
                unavailable.add(unavailableDto(primary, machines.get(primary.id()), "FULLY_BOOKED"));
                continue;
            }
            List<Resource> chosen = combo;
            List<Instant> starts = plansByVariant.get(best).stream()
                    .filter(plan -> isCombinationFree(chosen, plan, occupied))
                    .map(SlotPlan::start)
                    .toList();
            options.add(searchOption(best.config(), chosen, earliest, starts, machines, declaredLoadKg));
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
                    SELECT id, resource_code, display_name FROM service_resources
                    WHERE resource_type = ? AND enabled = FALSE
                    """, rs -> {
                unavailable.add(new UnavailableMachineDto(rs.getObject("id", UUID.class), primaryType,
                        rs.getString("resource_code"), rs.getString("display_name"), null, "DISABLED"));
            }, primaryType);
        }

        options.sort(Comparator.comparing(MachineSearchOptionDto::earliestStart)
                .thenComparing(MachineSearchOptionDto::price)
                .thenComparing(option -> option.resources().getFirst().resourceCode()));
        unavailable.sort(Comparator.comparing(UnavailableMachineDto::resourceCode));
        int durationMinutes = offsetMinutes(buildPlan(config, Instant.EPOCH).expectedEnd());
        return new MachineSearchResponse(config.code(), zone.getId(), from, to, firstStart,
                durationMinutes, config.bufferMinutes(), options, unavailable);
    }

    /** One way to size the service: one cycle per stage, with only the resources of each stage that run that long. */
    private record Variant(ServiceConfig config, Map<String, List<Resource>> resourcesByType) {}

    /**
     * V71: machines of one type may run for different cycles (a 10 kg and a 15 kg washer). The service is planned once
     * per combination of distinct cycles, each time with only the machines of those cycles, so every machine holds
     * exactly its own cycle. Resources that are not priced machines keep the service's duration. Stores have few
     * distinct cycles, so this stays a handful of plans per start time.
     */
    private List<Variant> variants(ServiceConfig config, Map<String, List<Resource>> resourcesByType) {
        Map<UUID, Integer> cycles = queries.machineCycles();
        List<Map.Entry<List<Integer>, Map<String, List<Resource>>>> partial =
                List.of(Map.entry(List.of(), Map.of()));
        for (String type : config.requirements()) {
            Map<Integer, List<Resource>> groups = new java.util.LinkedHashMap<>();
            for (Resource resource : resourcesByType.getOrDefault(type, List.of())) {
                groups.computeIfAbsent(cycles.get(resource.id()), ignored -> new ArrayList<>()).add(resource);
            }
            if (groups.isEmpty()) {
                groups.put(null, List.of());
            }
            List<Map.Entry<List<Integer>, Map<String, List<Resource>>>> next = new ArrayList<>();
            for (var entry : partial) {
                for (var group : groups.entrySet()) {
                    List<Integer> minutes = new ArrayList<>(entry.getKey());
                    minutes.add(group.getKey());
                    Map<String, List<Resource>> byType = new java.util.LinkedHashMap<>(entry.getValue());
                    byType.put(type, List.copyOf(group.getValue()));
                    next.add(new java.util.AbstractMap.SimpleImmutableEntry<>(minutes, byType));
                }
            }
            partial = next;
        }
        return partial.stream()
                .map(entry -> new Variant(BookingResourceQueries.withStageMinutes(config, entry.getKey()), entry.getValue()))
                .toList();
    }

    /** Latest end of any machine's allocation for a booking starting at {@code start}, over all variants. */
    private static Instant latestAllocationEnd(List<Variant> variants, Instant start) {
        return variants.stream()
                .flatMap(variant -> buildPlan(variant.config(), start).allocationPlans().stream())
                .map(AllocationPlan::until)
                .max(Comparator.naturalOrder())
                .orElse(start);
    }

    private static Map<UUID, Resource> byId(Map<String, List<Resource>> resourcesByType) {
        Map<UUID, Resource> result = new java.util.HashMap<>();
        resourcesByType.values().forEach(list -> list.forEach(resource -> result.put(resource.id(), resource)));
        return result;
    }

    private MachineSearchOptionDto searchOption(ServiceConfig config,
                                                List<Resource> resources,
                                                SlotPlan plan,
                                                List<Instant> starts,
                                                Map<UUID, Machine> machines,
                                                BigDecimal loadKg) {
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
                reservedUntil, queries.price(config, resources.stream().map(Resource::id).toList(), loadKg).total(),
                "VND", totalMinutes, toStartRanges(starts), plan.timeline());
    }

    private UnavailableMachineDto unavailableDto(Resource resource, Machine machine, String reason) {
        return new UnavailableMachineDto(resource.id(), resource.type(), resource.code(), resource.name(),
                machine == null ? null : machine.getCapacityKg(), reason);
    }

    private static String unavailableReason(Machine machine) {
        if (machine.isUnderMaintenance()) return "MAINTENANCE";
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
                    result.add(new AvailabilityInterval(start, end, "OPERATING", "Cửa hàng mở cửa",
                            null, null, null));
                }
                start = interval[0];
                end = interval[1];
            }
        }
        if (start != null) {
            result.add(new AvailabilityInterval(start, end, "OPERATING", "Cửa hàng mở cửa",
                    null, null, null));
        }
        return result;
    }

    private List<AvailabilityInterval> unavailableIntervals(
                                                            ServiceConfig config,
                                                            Instant dayStart,
                                                            Instant dayEnd,
                                                            Map<UUID, List<OccupiedInterval>> occupied,
                                                            Map<UUID, Resource> resourcesById) {
        List<AvailabilityInterval> result = new ArrayList<>();
        Set<String> requiredTypes = Set.copyOf(config.requirements());
        for (Machine machine : machineRepository.findAll()) {
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
                    SELECT id, resource_code FROM service_resources
                    WHERE resource_type = ? AND enabled = FALSE
                    """, rs -> {
                UUID resourceId = rs.getObject("id", UUID.class);
                String code = rs.getString("resource_code");
                result.add(new AvailabilityInterval(dayStart, dayEnd, "UNAVAILABLE",
                        "Tài nguyên không khả dụng: " + code, type, code, resourceId));
            }, type);
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
                                        List<RecommendationDto> result,
                                        BigDecimal loadKg,
                                        Map<UUID, BigDecimal> capacities) {
        if (index == candidates.size()) {
            List<RecommendedResourceDto> resources = selected.stream()
                    .map(resource -> new RecommendedResourceDto(resource.id(), resource.type(),
                            resource.code(), resource.name(), resource.location(),
                            "AVAILABLE_AT_SELECTED_TIME", capacities.get(resource.id())))
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
                    plan.expectedEnd(), queries.price(config, selected.stream().map(Resource::id).toList(), loadKg).total(), "VND",
                    Math.toIntExact((plan.expectedEnd().toEpochMilli() - plan.start().toEpochMilli()) / 60_000),
                    resources, plan.timeline()));
            return;
        }
        for (Resource resource : candidates.get(index)) {
            if (result.size() >= 20) return;
            selected.add(resource);
            collectRecommendations(config, plan, candidates, index + 1, selected, result, loadKg, capacities);
            selected.removeLast();
        }
    }

    /** BR-26: washers / dryers smaller than the load are not offered. Machines without a capacity always fit. */
    private static void dropTooSmall(Map<String, List<Resource>> resourcesByType, List<Machine> machines,
                                     BigDecimal loadKg) {
        if (loadKg == null) {
            return;
        }
        Map<UUID, Machine> byId = new java.util.HashMap<>();
        machines.forEach(machine -> byId.put(machine.getId(), machine));
        resourcesByType.replaceAll((type, list) -> list.stream().filter(resource -> {
            Machine machine = byId.get(resource.id());
            return machine == null || machine.getCapacityKg() == null
                    || machine.getCapacityKg().compareTo(loadKg) >= 0;
        }).toList());
    }

    /** Largest rated load among the bookable washers: the most a customer can book; null when none is set. */
    private BigDecimal maxWasherCapacityKg() {
        return machineRepository.findAll().stream()
                .filter(machine -> machine.getType() == MachineType.WASHER && queries.isOperationallyUsable(machine))
                .map(Machine::getCapacityKg)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }
}
