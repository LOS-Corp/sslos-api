package com.laundry.booking.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.booking.dto.BookingDtos;
import com.laundry.booking.dto.MachineProgramDtos.MachineProgramDto;
import com.laundry.branch.entity.Branch;
import com.laundry.branch.entity.BranchStatus;
import com.laundry.branch.repository.BranchRepository;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.transaction.entity.MachineReservation;
import com.laundry.transaction.entity.ReservationStatus;
import com.laundry.transaction.entity.ServiceType;
import com.laundry.transaction.entity.Transaction;
import com.laundry.transaction.entity.TransactionStatus;
import com.laundry.transaction.repository.MachineReservationRepository;
import com.laundry.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.*;
import static com.laundry.booking.dto.BookingConfigDtos.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledBookingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BranchRepository branchRepository;
    private final MachineRepository machineRepository;
    private final MachineReservationRepository machineReservationRepository;
    private final TransactionRepository transactionRepository;
    private final BranchBookingReadinessService readinessService;
    private final com.laundry.machine.runtime.MachineRuntimeStateService runtimeStateService;
    private final MachineProgramService programService;

    /** Assumed remaining time of a running cycle when the device has not reported one. */
    @org.springframework.beans.factory.annotation.Value("${app.booking.active-session-fallback-minutes:60}")
    private long activeSessionFallbackMinutes;

    /** Longest start-time window one machine search may cover (allows overnight / 24/7 windows). */
    static final java.time.Duration MAX_SEARCH_WINDOW = java.time.Duration.ofHours(48);

    /** Machine states that mean "in use right now" but bookable once the session ends. */
    static final Set<MachineStatus> SESSION_STATUSES = java.util.EnumSet.of(
            MachineStatus.WAITING_START, MachineStatus.RUNNING,
            MachineStatus.PAUSED, MachineStatus.COMPLETED);

    @org.springframework.beans.factory.annotation.Value("${app.booking.hold-duration-seconds:600}")
    private long holdDurationSeconds;

    record ServiceConfig(
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

    record Resource(String type, UUID id, String code, String name, String location, String status) {
        Resource(String type, UUID id) {
            this(type, id, null, null, null, null);
        }
    }

    record AllocationPlan(
            String type,
            String phase,
            Instant from,
            Instant until
    ) {}

    record SlotPlan(
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
                        requirementsFor(s.code())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<BranchServiceDto> listBranches(String serviceCode) {
        ServiceConfig config = serviceDefinition(serviceCode);
        List<Branch> branches = branchRepository.findByStatusOrderByNameAsc(BranchStatus.ACTIVE);
        List<BranchServiceDto> result = new ArrayList<>();
        for (Branch branch : branches) {
            ServiceConfig offered = customerOffering(branch.getId(), serviceCode, null, null);
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
                    openingHours(branch.getId()), resources));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public TimelineAvailabilityResponse timelineAvailability(UUID branchId, String serviceCode, LocalDate date,
                                                             String washProgram, String dryProgram) {
        ServiceConfig config = customerOffering(branchId, serviceCode, washProgram, dryProgram);
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "This service is not configured for the selected branch");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(config.timezone());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Branch timezone is invalid");
        }
        LocalDate today = LocalDate.now(zone);
        if (date.isBefore(today) || date.isAfter(today.plusDays(60))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Booking date must be within the next 60 days");
        }

        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        SlotPlan lastStartPlan = buildPlan(config, dayEnd.minusSeconds(60));
        Instant queryEnd = lastStartPlan.allocationPlans().stream()
                .map(AllocationPlan::until)
                .max(Comparator.naturalOrder())
                .orElse(dayEnd);
        Map<DayOfWeek, ScheduleHours> weeklyHours = scheduleHours(branchId);
        List<AvailabilityInterval> operatingIntervals =
                operatingIntervals(weeklyHours, zone, dayStart, dayEnd);
        List<String> requirements = config.requirements();
        Map<String, List<Resource>> resourcesByType = new java.util.LinkedHashMap<>();
        Map<UUID, Resource> resourcesById = new java.util.HashMap<>();
        for (String requirement : requirements) {
            List<Resource> resources = resourcesFor(branchId, requirement);
            resourcesByType.put(requirement, resources);
            resources.forEach(resource -> resourcesById.put(resource.id(), resource));
        }

        Map<UUID, List<OccupiedInterval>> occupiedByResource =
                occupiedIntervals(branchId, zone, dayStart, queryEnd, resourcesById, config.bufferMinutes());
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Start time must be aligned to a whole minute");
        }
        ServiceConfig config = customerOffering(branchId, serviceCode, washProgram, dryProgram);
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "This service is not configured for the selected branch");
        }
        SlotPlan plan = buildPlan(config, desiredStartTime);
        boolean preferredEndSatisfied = preferredEndTime == null
                || !plan.expectedEnd().isAfter(preferredEndTime);
        List<RecommendationDto> recommendations = new ArrayList<>();
        if (preferredEndSatisfied && isWithinOpeningHours(config, desiredStartTime, plan)) {
            List<List<Resource>> candidates = new ArrayList<>();
            for (int i = 0; i < config.requirements().size(); i++) {
                AllocationPlan allocation = plan.allocationPlans().get(i);
                candidates.add(resourcesFor(config.branchId(), config.requirements().get(i)).stream()
                        .filter(resource -> !resourceBusy(resource, allocation, null, null,
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
                                               String washProgram, String dryProgram) {
        ServiceConfig config = customerOffering(branchId, serviceCode, washProgram, dryProgram);
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "This service is not configured for the selected branch");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(config.timezone());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Branch timezone is invalid");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The search window must end after it starts");
        }
        if (java.time.Duration.between(from, to).compareTo(MAX_SEARCH_WINDOW) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The search window can cover at most 48 hours");
        }
        Instant now = Instant.now();
        if (from.isAfter(now.plus(java.time.Duration.ofDays(60)))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Bookings can be made at most 60 days ahead");
        }
        Instant firstStart = ceilToMinute(from.isAfter(now) ? from : now);
        Instant lastStart = Instant.ofEpochSecond(Math.floorDiv(to.getEpochSecond(), 60) * 60);
        if (lastStart.isBefore(firstStart)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The selected time window has already passed");
        }

        List<String> requirements = config.requirements();
        Map<String, List<Resource>> resourcesByType = new java.util.LinkedHashMap<>();
        Map<UUID, Resource> resourcesById = new java.util.HashMap<>();
        for (String requirement : requirements) {
            List<Resource> resources = resourcesFor(branchId, requirement);
            resourcesByType.put(requirement, resources);
            resources.forEach(resource -> resourcesById.put(resource.id(), resource));
        }
        Map<UUID, Machine> machines = new java.util.HashMap<>();
        machineRepository.findByBranchId(branchId).forEach(machine -> machines.put(machine.getId(), machine));

        Instant queryEnd = buildPlan(config, lastStart).allocationPlans().stream()
                .map(AllocationPlan::until)
                .max(Comparator.naturalOrder())
                .orElse(lastStart);
        Map<UUID, List<OccupiedInterval>> occupied = occupiedIntervals(
                branchId, zone, firstStart, queryEnd, resourcesById, config.bufferMinutes());

        // Every whole minute in the window whose service fits the opening hours.
        boolean locker = "ASSISTED_LOCKER".equals(config.mode());
        Map<DayOfWeek, ScheduleHours> weeklyHours = scheduleHours(branchId);
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
        List<UnavailableMachineDto> unavailable = new ArrayList<>();
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
                    .filter(machine -> machine.getType() == machineType && !isOperationallyUsable(machine))
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

    /** {@code primary} plus the first free companion for every other requirement, or null. */
    static List<Resource> freeCombination(Resource primary,
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

    static boolean isCombinationFree(List<Resource> resources,
                                             SlotPlan plan,
                                             Map<UUID, List<OccupiedInterval>> occupied) {
        for (int i = 0; i < resources.size(); i++) {
            if (!isFree(resources.get(i), plan.allocationPlans().get(i), occupied)) {
                return false;
            }
        }
        return true;
    }

    static boolean isFree(Resource resource, AllocationPlan allocation, Map<UUID, List<OccupiedInterval>> occupied) {
        return occupied.getOrDefault(resource.id(), List.of()).stream()
                .noneMatch(interval -> intervalsOverlap(allocation.from(), allocation.until(),
                        interval.start(), interval.end()));
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

    static Instant ceilToMinute(Instant instant) {
        long seconds = instant.getEpochSecond() + (instant.getNano() > 0 ? 1 : 0);
        return Instant.ofEpochSecond(Math.floorDiv(seconds + 59, 60) * 60);
    }

    @Transactional
    public BookingDto createHold(UUID customerId, CreateHoldRequest request) {
        if (!isMinuteAligned(request.startTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Start time must be aligned to a whole minute");
        }
        if (request.idempotencyKey().length() > 120) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idempotencyKey is too long");
        }
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))",
                Object.class, "booking-hold:" + request.idempotencyKey());

        UUID existingBooking = jdbc.query("""
                SELECT id FROM scheduled_bookings WHERE idempotency_key = ?
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, request.idempotencyKey());
        if (existingBooking != null) {
            return getBooking(customerId, existingBooking);
        }

        lockBranchConfiguration(request.branchId());
        ServiceConfig offered = offering(request.branchId(), request.serviceCode());
        if (offered == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "This service is not configured for the selected branch");
        }
        List<MachineProgramDto> programs = programService.resolve(
                offered.requirements(), request.washProgram(), request.dryProgram());
        ServiceConfig config = withPrograms(offered, programs);
        Branch branch = branchRepository.findById(request.branchId())
                .filter(candidate -> candidate.getStatus() == BranchStatus.ACTIVE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        SlotPlan plan = buildPlan(config, request.startTime());
        if (request.resourceIds().size() != config.requirements().size()
                || request.resourceIds().stream().distinct().count() != request.resourceIds().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Select exactly one unique resource for each service requirement");
        }
        List<Resource> resources = new ArrayList<>();
        for (int i = 0; i < config.requirements().size(); i++) {
            String requiredType = config.requirements().get(i);
            UUID selectedId = request.resourceIds().get(i);
            Resource selectedResource = resourcesFor(config.branchId(), requiredType).stream()
                    .filter(resource -> resource.id().equals(selectedId))
                    .findFirst().orElse(null);
            if (selectedResource == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A selected resource is not compatible with this service");
            }
            resources.add(selectedResource);
        }
        if (!isWithinOpeningHours(config, request.startTime(), plan)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The requested time is outside branch opening hours");
        }
        lockResources(resources);
        validateSelectedResources(request.branchId(), resources);
        for (int i = 0; i < resources.size(); i++) {
            if (resourceBusy(resources.get(i), plan.allocationPlans().get(i), null, null,
                    config.bufferMinutes())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The selected time was just booked. Please choose another slot.");
            }
        }

        Instant now = Instant.now();
        Instant holdExpiresAt = now.plusSeconds(holdDurationSeconds);
        Transaction transaction = Transaction.builder()
                .customerId(customerId)
                .serviceType("ASSISTED_LOCKER".equals(config.mode())
                        ? ServiceType.LAUNDRY_SERVICE : ServiceType.SELF_SERVICE)
                .totalAmount(config.price())
                .status(TransactionStatus.PENDING_PAYMENT)
                .expiresAt(LocalDateTime.ofInstant(holdExpiresAt, ZoneOffset.UTC))
                .build();
        transaction = transactionRepository.saveAndFlush(transaction);

        UUID bookingId = UUID.randomUUID();
        String timelineJson;
        try {
            timelineJson = objectMapper.writeValueAsString(plan.timeline());
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize booking timeline", e);
        }
        jdbc.update("""
                INSERT INTO scheduled_bookings
                  (id, transaction_id, branch_id, service_code, status, start_time,
                   expected_end_time, hold_expires_at, timeline, idempotency_key, programs)
                VALUES (?, ?, ?, ?, 'HELD', ?, ?, ?, CAST(? AS JSONB), ?, CAST(? AS JSONB))
                """, bookingId, transaction.getId(), branch.getId(), config.code(),
                OffsetDateTime.ofInstant(plan.start(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(plan.expectedEnd(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(holdExpiresAt, ZoneOffset.UTC),
                timelineJson, request.idempotencyKey(), programService.toJson(programs));

        for (int i = 0; i < resources.size(); i++) {
            Resource resource = resources.get(i);
            AllocationPlan allocation = plan.allocationPlans().get(i);
            jdbc.update("""
                    INSERT INTO booking_resource_allocations
                      (booking_id, resource_id, resource_type, phase, occupied_from, occupied_until)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, bookingId, resource.id(), resource.type(), allocation.phase(),
                    OffsetDateTime.ofInstant(allocation.from(), ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(allocation.until(), ZoneOffset.UTC));

            if ("WASHER".equals(resource.type()) || "DRYER".equals(resource.type())) {
                MachineReservation reservation = MachineReservation.builder()
                        .transaction(transaction)
                        .machineId(resource.id())
                        .startTime(LocalDateTime.ofInstant(allocation.from(), ZoneId.systemDefault()))
                        .endTime(LocalDateTime.ofInstant(allocation.until(), ZoneId.systemDefault()))
                        .status(ReservationStatus.RESERVED)
                        .build();
                transaction.addMachineReservation(reservation);
                machineReservationRepository.save(reservation);
            }
        }
        transactionRepository.save(transaction);
        return getBooking(customerId, bookingId);
    }

    @Transactional(readOnly = true)
    public BookingDto getBooking(UUID customerId, UUID bookingId) {
        List<BookingDto> bookings = jdbc.query("""
                SELECT sb.id booking_id, t.id transaction_id, sd.code service_code,
                       sd.name service_name, sd.service_mode, sb.status booking_status,
                       t.status transaction_status, sb.branch_id, b.name branch_name,
                       b.address branch_address, b.phone branch_phone, sb.start_time,
                       sb.expected_end_time, sb.hold_expires_at, t.total_amount,
                       sb.timeline::text timeline, sb.programs::text programs,
                       p.payment_id, p.status payment_status, p.checkout_url payment_url, p.qr_code,
                       allocated.booking_resources
                FROM scheduled_bookings sb
                JOIN transactions t ON t.id = sb.transaction_id
                JOIN booking_service_definitions sd ON sd.code = sb.service_code
                JOIN branches b ON b.id = sb.branch_id
                LEFT JOIN LATERAL (
                    SELECT id payment_id, status, checkout_url, qr_code
                    FROM payments WHERE transaction_id = t.id
                    ORDER BY created_at DESC LIMIT 1
                ) p ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COALESCE(jsonb_agg(jsonb_build_object(
                        'resourceId', a.resource_id,
                        'resourceType', a.resource_type,
                        'resourceCode', COALESCE(m.machine_code, r.resource_code),
                        'displayName', COALESCE(NULLIF(m.display_name, ''), r.display_name, m.machine_code),
                        'location', m.location_description,
                        'status', COALESCE(m.status, CASE WHEN r.enabled THEN 'ASSIGNED' ELSE 'DISABLED' END)
                    ) ORDER BY a.resource_type), '[]'::jsonb)::text booking_resources
                    FROM booking_resource_allocations a
                    LEFT JOIN machines m ON m.id = a.resource_id
                    LEFT JOIN branch_service_resources r ON r.id = a.resource_id
                    WHERE a.booking_id = sb.id
                ) allocated ON TRUE
                WHERE sb.id = ? AND t.customer_id = ?
                """, (rs, row) -> mapBooking(rs), bookingId, customerId);
        if (bookings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
        }
        return bookings.getFirst();
    }

    @Transactional(readOnly = true)
    public List<BookingDto> getCustomerBookings(UUID customerId) {
        return jdbc.query("""
                SELECT sb.id booking_id, t.id transaction_id, sd.code service_code,
                       sd.name service_name, sd.service_mode, sb.status booking_status,
                       t.status transaction_status, sb.branch_id, b.name branch_name,
                       b.address branch_address, b.phone branch_phone, sb.start_time,
                       sb.expected_end_time, sb.hold_expires_at, t.total_amount,
                       sb.timeline::text timeline, sb.programs::text programs,
                       p.payment_id, p.status payment_status, p.checkout_url payment_url, p.qr_code,
                       allocated.booking_resources
                FROM scheduled_bookings sb
                JOIN transactions t ON t.id = sb.transaction_id
                JOIN booking_service_definitions sd ON sd.code = sb.service_code
                JOIN branches b ON b.id = sb.branch_id
                LEFT JOIN LATERAL (
                    SELECT id payment_id, status, checkout_url, qr_code
                    FROM payments WHERE transaction_id = t.id
                    ORDER BY created_at DESC LIMIT 1
                ) p ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COALESCE(jsonb_agg(jsonb_build_object(
                        'resourceId', a.resource_id,
                        'resourceType', a.resource_type,
                        'resourceCode', COALESCE(m.machine_code, r.resource_code),
                        'displayName', COALESCE(NULLIF(m.display_name, ''), r.display_name, m.machine_code),
                        'location', m.location_description,
                        'status', COALESCE(m.status, CASE WHEN r.enabled THEN 'ASSIGNED' ELSE 'DISABLED' END)
                    ) ORDER BY a.resource_type), '[]'::jsonb)::text booking_resources
                    FROM booking_resource_allocations a
                    LEFT JOIN machines m ON m.id = a.resource_id
                    LEFT JOIN branch_service_resources r ON r.id = a.resource_id
                    WHERE a.booking_id = sb.id
                ) allocated ON TRUE
                WHERE t.customer_id = ?
                ORDER BY sb.created_at DESC
                """, (rs, row) -> mapBooking(rs), customerId);
    }

    @Transactional
    public UUID prepareCheckout(UUID customerId, UUID bookingId) {
        List<UUID> transactionIds = jdbc.query("""
                SELECT sb.transaction_id
                FROM scheduled_bookings sb
                JOIN transactions t ON t.id = sb.transaction_id
                WHERE sb.id = ? AND t.customer_id = ?
                  AND sb.status = 'HELD'
                  AND sb.hold_expires_at > NOW()
                  AND sb.start_time > NOW()
                FOR UPDATE OF sb, t
                """, (rs, row) -> rs.getObject(1, UUID.class), bookingId, customerId);
        if (transactionIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking hold has expired");
        }
        return transactionIds.getFirst();
    }

    @Transactional
    public void cancelBooking(UUID customerId, UUID bookingId) {
        BookingDto booking = getBooking(customerId, bookingId);
        if (!"HELD".equals(booking.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking cannot be cancelled");
        }
        jdbc.update("""
                UPDATE scheduled_bookings SET status = 'CANCELLED', updated_at = NOW()
                WHERE id = ?
                """, bookingId);
        transactionRepository.findById(booking.transactionId()).ifPresent(transaction -> {
            transaction.setStatus(TransactionStatus.CANCELLED);
            transactionRepository.save(transaction);
        });
        machineReservationRepository.findAllByTransactionId(booking.transactionId()).forEach(reservation -> {
            reservation.setStatus(ReservationStatus.CANCELLED);
            machineReservationRepository.save(reservation);
        });
    }

    public boolean hasScheduledBooking(UUID transactionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_bookings WHERE transaction_id = ?",
                Integer.class, transactionId);
        return count != null && count > 0;
    }

    @Transactional
    public void confirmPayment(UUID transactionId) {
        Transaction transaction = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        List<UUID> bookingIds = jdbc.query("""
                SELECT id FROM scheduled_bookings WHERE transaction_id = ? FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), transactionId);
        if (bookingIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Scheduled booking not found");
        }
        UUID bookingId = bookingIds.getFirst();
        String bookingStatus = jdbc.queryForObject(
                "SELECT status FROM scheduled_bookings WHERE id = ?",
                String.class, bookingId);
        if ("CONFIRMED".equals(bookingStatus)) {
            return;
        }
        if ("CANCELLED".equals(bookingStatus)) {
            transaction.setStatus(TransactionStatus.PAID);
            transaction.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
            transactionRepository.save(transaction);
            jdbc.update("""
                    UPDATE scheduled_bookings SET status = 'PAYMENT_REVIEW_REQUIRED',
                        reconciliation_reason = 'Payment succeeded after cancellation; refund or manual reconciliation is required',
                        updated_at = NOW()
                    WHERE id = ?
                    """, bookingId);
            log.error("Cancelled booking {} was paid after cancellation and requires reconciliation", bookingId);
            return;
        }
        if (!"HELD".equals(bookingStatus) && !"EXPIRED".equals(bookingStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Booking is not eligible for payment confirmation");
        }

        List<AllocationPlan> allocations = jdbc.query("""
                SELECT resource_type, phase, occupied_from, occupied_until
                FROM booking_resource_allocations WHERE booking_id = ?
                ORDER BY resource_id
                """, (rs, row) -> new AllocationPlan(
                rs.getString("resource_type"), rs.getString("phase"),
                rs.getTimestamp("occupied_from").toInstant(),
                rs.getTimestamp("occupied_until").toInstant()), bookingId);
        List<Resource> resources = jdbc.query("""
                SELECT resource_type, resource_id FROM booking_resource_allocations
                WHERE booking_id = ? ORDER BY resource_id
                """, (rs, row) -> new Resource(rs.getString(1), rs.getObject(2, UUID.class)), bookingId);
        lockResources(resources);

        boolean holdExpired = !"PENDING_PAYMENT".equals(transaction.getStatus().name())
                || jdbc.queryForObject("""
                    SELECT hold_expires_at <= NOW()
                    FROM scheduled_bookings WHERE id = ?
                    """, Boolean.class, bookingId);
        boolean startPassed = jdbc.queryForObject("""
                SELECT start_time <= NOW() FROM scheduled_bookings WHERE id = ?
                """, Boolean.class, bookingId);
        boolean conflicts = false;
        for (int i = 0; i < resources.size(); i++) {
            if (resourceBusy(resources.get(i), allocations.get(i), bookingId, transactionId)) {
                conflicts = true;
                break;
            }
        }
        if (startPassed || (holdExpired && conflicts)) {
            transaction.setStatus(TransactionStatus.PAID);
            transaction.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
            transactionRepository.save(transaction);
            jdbc.update("""
                    UPDATE scheduled_bookings SET status = 'PAYMENT_REVIEW_REQUIRED',
                        reconciliation_reason = ?,
                        updated_at = NOW()
                    WHERE id = ?
                    """, startPassed
                    ? "Payment succeeded after the booked start time; refund or manual reconciliation is required"
                    : "Payment succeeded after the hold expired and resources are no longer available",
                    bookingId);
            log.error("Paid booking {} requires reconciliation: startPassed={}, holdExpired={}, conflicts={}",
                    bookingId, startPassed, holdExpired, conflicts);
            return;
        }

        transaction.setStatus(TransactionStatus.PAID);
        transaction.setPaidAt(LocalDateTime.now(ZoneOffset.UTC));
        transactionRepository.save(transaction);
        jdbc.update("""
                UPDATE scheduled_bookings SET status = 'CONFIRMED', updated_at = NOW()
                WHERE id = ?
                """, bookingId);
        machineReservationRepository.findAllByTransactionId(transactionId).forEach(reservation -> {
            reservation.setStatus(ReservationStatus.CONFIRMED);
            machineReservationRepository.save(reservation);
        });
    }

    @Transactional
    public void cancelForPaymentFailure(UUID transactionId) {
        Transaction transaction = transactionRepository.findByIdForUpdate(transactionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
        jdbc.update("""
                UPDATE scheduled_bookings SET status = 'CANCELLED', updated_at = NOW()
                WHERE transaction_id = ? AND status IN ('HELD', 'EXPIRED')
                """, transactionId);
        if (transaction.getStatus() == TransactionStatus.PENDING_PAYMENT
                || transaction.getStatus() == TransactionStatus.EXPIRED) {
            transaction.setStatus(TransactionStatus.CANCELLED);
            transactionRepository.save(transaction);
        }
        machineReservationRepository.findAllByTransactionId(transactionId).forEach(reservation -> {
            if (reservation.getStatus() == ReservationStatus.RESERVED
                    || reservation.getStatus() == ReservationStatus.EXPIRED) {
                reservation.setStatus(ReservationStatus.CANCELLED);
                machineReservationRepository.save(reservation);
            }
        });
    }

    @Transactional
    public void expireBookingHolds() {
        List<UUID> expired = jdbc.query("""
                UPDATE scheduled_bookings SET status = 'EXPIRED', updated_at = NOW()
                WHERE status = 'HELD' AND hold_expires_at <= NOW()
                RETURNING transaction_id
                """, (rs, row) -> rs.getObject(1, UUID.class));
        if (expired.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(expired.size(), "?"));
        Object[] expiredIds = expired.toArray();
        jdbc.update("""
                UPDATE transactions SET status = 'EXPIRED', updated_at = NOW()
                WHERE id IN (%s) AND status = 'PENDING_PAYMENT'
                """.formatted(placeholders), expiredIds);
        jdbc.update("""
                UPDATE machine_reservations SET status = 'EXPIRED', updated_at = NOW()
                WHERE transaction_id IN (%s) AND status = 'RESERVED'
                """.formatted(placeholders), expiredIds);
        log.info("Expired {} booking hold(s)", expired.size());
    }

    @Transactional
    public void configureHours(UUID branchId, BookingHoursRequest request) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        lockBranchConfiguration(branchId);
        ZoneId zone;
        try {
            zone = ZoneId.of(request.timezone());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid IANA timezone");
        }
        if (request.days().stream().map(DayHours::dayOfWeek).distinct().count() != request.days().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate dayOfWeek");
        }
        validateScheduleImpact(branchId, request);
        jdbc.update("""
                INSERT INTO branch_booking_settings(branch_id, timezone) VALUES (?, ?)
                ON CONFLICT (branch_id) DO UPDATE SET timezone = EXCLUDED.timezone
                """, branchId, zone.getId());
        jdbc.update("DELETE FROM branch_booking_hours WHERE branch_id = ?", branchId);
        request.days().forEach(day -> {
            if (day.opensAt().equals(day.closesAt())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Opening and closing times cannot be identical");
            }
            jdbc.update("""
                    INSERT INTO branch_booking_hours(branch_id, day_of_week, opens_at, closes_at)
                    VALUES (?, ?, ?, ?)
                    """, branchId, day.dayOfWeek().getValue(), day.opensAt(), day.closesAt());
        });
    }

    @Transactional
    public void configureOffering(UUID branchId, String serviceCode, BranchOfferingRequest request) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        lockBranchConfiguration(branchId);
        serviceDefinition(serviceCode);
        List<Boolean> existing = jdbc.query("""
                SELECT enabled FROM branch_service_offerings
                WHERE branch_id = ? AND service_code = ?
                """, (rs, row) -> rs.getBoolean(1), branchId, serviceCode);
        ServiceConfig existingConfig = offering(branchId, serviceCode);
        boolean currentlyEnabled = !existing.isEmpty() && existing.getFirst();
        boolean durationChanged = existingConfig != null
                && ((request.processingMinutes() != null
                && request.processingMinutes() != existingConfig.processingMinutes())
                || (request.secondaryProcessingMinutes() != null
                && request.secondaryProcessingMinutes() != existingConfig.secondaryProcessingMinutes()));
        if (hasFutureReservations(branchId, serviceCode)
                && ((currentlyEnabled && !request.enabled()) || durationChanged)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot disable or change service duration while future bookings exist");
        }
        jdbc.update("""
                INSERT INTO branch_service_offerings
                  (branch_id, service_code, enabled, price, processing_minutes_override,
                   secondary_processing_minutes_override)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (branch_id, service_code) DO UPDATE SET
                  enabled = EXCLUDED.enabled,
                  price = EXCLUDED.price,
                  processing_minutes_override = EXCLUDED.processing_minutes_override,
                  secondary_processing_minutes_override = EXCLUDED.secondary_processing_minutes_override
                """, branchId, serviceCode, request.enabled(), request.price(),
                request.processingMinutes(), request.secondaryProcessingMinutes());
    }

    @Transactional
    public ResourceDto addResource(UUID branchId, CreateResourceRequest request) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        lockBranchConfiguration(branchId);
        String resourceType = request.resourceType().trim().toUpperCase();
        if (!Set.of("IRON_STATION", "LOCKER").contains(resourceType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "resourceType must be IRON_STATION or LOCKER");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO branch_service_resources
                  (id, branch_id, resource_type, resource_code, display_name, enabled)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """, id, branchId, resourceType, request.resourceCode().trim(),
                request.displayName().trim());
        return new ResourceDto(id, branchId, resourceType, request.resourceCode().trim(),
                request.displayName().trim(), true);
    }

    @Transactional(readOnly = true)
    public List<ResourceDto> listResources(UUID branchId) {
        return jdbc.query("""
                SELECT id, branch_id, resource_type, resource_code, display_name, enabled
                FROM branch_service_resources WHERE branch_id = ? ORDER BY resource_type, resource_code
                """, (rs, row) -> new ResourceDto(
                rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                rs.getString("resource_type"), rs.getString("resource_code"),
                rs.getString("display_name"), rs.getBoolean("enabled")), branchId);
    }

    @Transactional(readOnly = true)
    public List<BranchHoursDto> getOpeningHours(UUID branchId) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        return openingHours(branchId);
    }

    @Transactional
    public void setResourceEnabled(UUID branchId, UUID resourceId, boolean enabled) {
        lockBranchConfiguration(branchId);
        if (!enabled && hasFutureResourceAllocations(resourceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot disable a resource with future holds or confirmed bookings");
        }
        int changed = jdbc.update("""
                UPDATE branch_service_resources SET enabled = ?
                WHERE branch_id = ? AND id = ?
                """, enabled, branchId, resourceId);
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch resource not found");
        }
    }

    @Transactional(readOnly = true)
    public BookingSettingsDto getBookingSettings(UUID branchId) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        List<BookingSettingsDto> settings = jdbc.query("""
                SELECT branch_id, timezone, turnover_buffer_minutes
                FROM branch_booking_settings WHERE branch_id = ?
                """, (rs, row) -> new BookingSettingsDto(
                rs.getObject("branch_id", UUID.class), rs.getString("timezone"),
                rs.getInt("turnover_buffer_minutes")), branchId);
        return settings.isEmpty() ? new BookingSettingsDto(branchId, null, 0) : settings.getFirst();
    }

    @Transactional
    public BookingSettingsDto configureBookingSettings(UUID branchId, BookingSettingsUpdate request) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        lockBranchConfiguration(branchId);
        ZoneId zone;
        try {
            zone = ZoneId.of(request.timezone());
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid IANA timezone");
        }
        String currentTimezone = jdbc.query("""
                SELECT timezone FROM branch_booking_settings WHERE branch_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, branchId);
        if (currentTimezone != null && !currentTimezone.equals(zone.getId())
                && hasFutureBranchBookings(branchId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot change timezone while future holds or confirmed bookings exist");
        }
        // A changed buffer only affects bookings made from now on; existing
        // allocations keep the occupancy window they were created with.
        Integer buffer = request.turnoverBufferMinutes();
        jdbc.update("""
                INSERT INTO branch_booking_settings(branch_id, timezone, turnover_buffer_minutes)
                VALUES (?, ?, COALESCE(?, 0))
                ON CONFLICT (branch_id) DO UPDATE SET timezone = EXCLUDED.timezone,
                    turnover_buffer_minutes = COALESCE(?, branch_booking_settings.turnover_buffer_minutes)
                """, branchId, zone.getId(), buffer, buffer);
        return getBookingSettings(branchId);
    }

    @Transactional(readOnly = true)
    public List<ServiceOfferingDto> listOfferings(UUID branchId) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        return jdbc.query("""
                SELECT d.code, d.name, d.service_mode, d.description,
                       d.processing_minutes, d.secondary_processing_minutes,
                       d.check_in_minutes, d.transfer_minutes, d.checkout_minutes,
                       o.enabled, o.price, o.processing_minutes_override,
                       o.secondary_processing_minutes_override
                FROM booking_service_definitions d
                LEFT JOIN branch_service_offerings o
                  ON o.service_code = d.code AND o.branch_id = ?
                WHERE d.active = TRUE
                ORDER BY CASE d.service_mode WHEN 'SELF_SERVICE' THEN 0 ELSE 1 END, d.name
                """, (rs, row) -> new ServiceOfferingDto(
                rs.getString("code"), rs.getString("name"), rs.getString("service_mode"),
                rs.getString("description"), rs.getObject("enabled") != null && rs.getBoolean("enabled"),
                rs.getBigDecimal("price"),
                rs.getObject("processing_minutes_override") == null
                        ? rs.getInt("processing_minutes") : rs.getInt("processing_minutes_override"),
                rs.getObject("secondary_processing_minutes_override") == null
                        ? rs.getInt("secondary_processing_minutes")
                        : rs.getInt("secondary_processing_minutes_override"),
                rs.getInt("check_in_minutes"), rs.getInt("transfer_minutes"),
                rs.getInt("checkout_minutes"), rs.getObject("enabled") != null), branchId);
    }

    @Transactional
    public ResourceDto updateResource(UUID branchId, UUID resourceId, ResourceUpdate request) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        lockBranchConfiguration(branchId);
        if (!request.enabled() && hasFutureResourceAllocations(resourceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot disable a resource with future holds or confirmed bookings");
        }
        int changed = jdbc.update("""
                UPDATE branch_service_resources
                SET resource_code = ?, display_name = ?, enabled = ?
                WHERE branch_id = ? AND id = ?
                """, request.resourceCode().trim(), request.displayName().trim(),
                request.enabled(), branchId, resourceId);
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch resource not found");
        }
        return jdbc.queryForObject("""
                SELECT id, branch_id, resource_type, resource_code, display_name, enabled
                FROM branch_service_resources WHERE id = ?
                """, (rs, row) -> new ResourceDto(
                rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                rs.getString("resource_type"), rs.getString("resource_code"),
                rs.getString("display_name"), rs.getBoolean("enabled")), resourceId);
    }

    private BookingDto mapBooking(ResultSet rs) throws SQLException {
        List<SlotPhaseDto> timeline;
        List<RecommendedResourceDto> resources;
        try {
            timeline = objectMapper.readValue(rs.getString("timeline"),
                    new TypeReference<>() {});
            resources = objectMapper.readValue(rs.getString("booking_resources"),
                    new TypeReference<>() {});
        } catch (Exception e) {
            throw new SQLException("Invalid stored booking details", e);
        }
        TimestampValue start = new TimestampValue(rs.getTimestamp("start_time").toInstant());
        TimestampValue end = new TimestampValue(rs.getTimestamp("expected_end_time").toInstant());
        TimestampValue holdExpires = new TimestampValue(rs.getTimestamp("hold_expires_at").toInstant());
        return new BookingDto(
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("booking_id", UUID.class),
                rs.getString("service_code"), rs.getString("service_name"),
                rs.getString("service_mode"), rs.getString("booking_status"),
                rs.getString("transaction_status"), rs.getObject("branch_id", UUID.class),
                rs.getString("branch_name"), rs.getString("branch_address"),
                rs.getString("branch_phone"), start.value(), end.value(), holdExpires.value(),
                rs.getBigDecimal("total_amount"), "VND", timeline, resources,
                programService.fromJson(rs.getString("programs")),
                rs.getObject("payment_id", UUID.class), rs.getString("payment_status"),
                rs.getString("payment_url"), rs.getString("qr_code"));
    }

    private record TimestampValue(Instant value) {}

    private ServiceConfig serviceDefinition(String code) {
        List<ServiceConfig> definitions = jdbc.query("""
                SELECT code, name, service_mode, description, processing_minutes,
                       secondary_processing_minutes, check_in_minutes, checkout_minutes,
                       transfer_minutes, pickup_deadline_hours
                FROM booking_service_definitions WHERE code = ? AND active = TRUE
                """, (rs, row) -> new ServiceConfig(
                rs.getString("code"), rs.getString("name"), rs.getString("service_mode"),
                rs.getString("description"), rs.getInt("processing_minutes"),
                rs.getInt("secondary_processing_minutes"), rs.getInt("check_in_minutes"),
                rs.getInt("checkout_minutes"), rs.getInt("transfer_minutes"),
                rs.getInt("pickup_deadline_hours"), null, null, null, null, null,
                requirementsFor(rs.getString("code")), 0), code);
        if (definitions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found");
        }
        return definitions.getFirst();
    }

    private ServiceConfig offering(UUID branchId, String serviceCode) {
        ServiceConfig definition = serviceDefinition(serviceCode);
        List<ServiceConfig> configured = jdbc.query("""
                SELECT o.price, COALESCE(o.processing_minutes_override, d.processing_minutes) processing_minutes,
                       COALESCE(o.secondary_processing_minutes_override, d.secondary_processing_minutes) secondary_processing_minutes,
                       s.timezone, s.turnover_buffer_minutes
                FROM branch_service_offerings o
                JOIN booking_service_definitions d ON d.code = o.service_code
                JOIN branch_booking_settings s ON s.branch_id = o.branch_id
                WHERE o.branch_id = ? AND o.service_code = ? AND o.enabled = TRUE AND d.active = TRUE
                """, (rs, row) -> new ServiceConfig(
                definition.code(), definition.name(), definition.mode(), definition.description(),
                rs.getInt("processing_minutes"), rs.getInt("secondary_processing_minutes"),
                definition.checkInMinutes(), definition.checkoutMinutes(), definition.transferMinutes(),
                definition.pickupDeadlineHours(), rs.getBigDecimal("price"), branchId,
                rs.getString("timezone"), null, null,
                definition.requirements(), rs.getInt("turnover_buffer_minutes")), branchId, serviceCode);
        if (configured.isEmpty()) {
            return null;
        }
        return configured.getFirst();
    }

    /** The branch offering with the chosen (or default) wash / dry programs applied. */
    private ServiceConfig customerOffering(UUID branchId, String serviceCode, String washProgram, String dryProgram) {
        ServiceConfig config = offering(branchId, serviceCode);
        if (config == null) {
            return null;
        }
        return withPrograms(config, programService.resolve(config.requirements(), washProgram, dryProgram));
    }

    /**
     * A machine program replaces the processing time of its machine (first
     * requirement = primary, second = secondary) and adds its surcharge.
     */
    static ServiceConfig withPrograms(ServiceConfig config, List<MachineProgramDto> programs) {
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

    private List<String> requirementsFor(String serviceCode) {
        return jdbc.query("""
                SELECT resource_type FROM booking_service_requirements
                WHERE service_code = ? ORDER BY sequence_no
                """, (rs, row) -> rs.getString(1), serviceCode);
    }

    private boolean hasConfiguredHours(UUID branchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM branch_booking_hours WHERE branch_id = ?",
                Integer.class, branchId);
        return count != null && count > 0;
    }

    private void lockBranchConfiguration(UUID branchId) {
        List<UUID> locked = jdbc.query("""
                SELECT id FROM branches WHERE id = ? FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), branchId);
        if (locked.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found");
        }
    }

    private boolean hasFutureBranchBookings(UUID branchId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM scheduled_bookings
                WHERE branch_id = ? AND start_time > NOW()
                  AND (status IN ('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING', 'READY_FOR_PICKUP')
                       OR (status = 'HELD' AND hold_expires_at > NOW()))
                """, Integer.class, branchId);
        return count != null && count > 0;
    }

    private boolean hasFutureReservations(UUID branchId, String serviceCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM scheduled_bookings
                WHERE branch_id = ? AND service_code = ? AND start_time > NOW()
                  AND (status IN ('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING', 'READY_FOR_PICKUP')
                       OR (status = 'HELD' AND hold_expires_at > NOW()))
                """, Integer.class, branchId, serviceCode);
        return count != null && count > 0;
    }

    private boolean hasFutureResourceAllocations(UUID resourceId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM booking_resource_allocations a
                JOIN scheduled_bookings b ON b.id = a.booking_id
                WHERE a.resource_id = ? AND b.start_time > NOW()
                  AND (b.status IN ('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING', 'READY_FOR_PICKUP')
                       OR (b.status = 'HELD' AND b.hold_expires_at > NOW()))
                """, Integer.class, resourceId);
        return count != null && count > 0;
    }

    private void validateScheduleImpact(UUID branchId, BookingHoursRequest request) {
        List<String> timezones = jdbc.query("""
                SELECT timezone FROM branch_booking_settings WHERE branch_id = ?
                """, (rs, row) -> rs.getString(1), branchId);
        if (timezones.isEmpty()) return;
        ZoneId zone = ZoneId.of(timezones.getFirst());
        Map<DayOfWeek, DayHours> schedule = request.days().stream()
                .collect(java.util.stream.Collectors.toMap(DayHours::dayOfWeek, day -> day));
        List<LocalDateTime[]> reservations = jdbc.query("""
                SELECT start_time, expected_end_time FROM scheduled_bookings
                WHERE branch_id = ? AND start_time > NOW()
                  AND (status IN ('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING', 'READY_FOR_PICKUP')
                       OR (status = 'HELD' AND hold_expires_at > NOW()))
                """, (rs, row) -> new LocalDateTime[] {
                rs.getTimestamp("start_time").toInstant().atZone(zone).toLocalDateTime(),
                rs.getTimestamp("expected_end_time").toInstant().atZone(zone).toLocalDateTime()
        }, branchId);
        Map<DayOfWeek, ScheduleHours> weeklyHours = schedule.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> new ScheduleHours(entry.getValue().opensAt(), entry.getValue().closesAt())));
        for (LocalDateTime[] reservation : reservations) {
            Instant start = reservation[0].atZone(zone).toInstant();
            Instant end = reservation[1].atZone(zone).toInstant();
            if (!isCoveredByOpeningHours(weeklyHours, zone, start, end)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Updated hours would close during an existing future booking");
            }
        }
    }

    private List<BranchHoursDto> openingHours(UUID branchId) {
        return jdbc.query("""
                SELECT day_of_week, opens_at, closes_at
                FROM branch_booking_hours WHERE branch_id = ?
                ORDER BY day_of_week
                """, (rs, row) -> new BranchHoursDto(
                DayOfWeek.of(rs.getInt("day_of_week")).name(),
                rs.getTime("opens_at").toLocalTime(),
                rs.getTime("closes_at").toLocalTime()), branchId);
    }

    record ScheduleHours(LocalTime opensAt, LocalTime closesAt) {}

    record OccupiedInterval(Instant start, Instant end) {}

    private Map<DayOfWeek, ScheduleHours> scheduleHours(UUID branchId) {
        return jdbc.query("""
                SELECT day_of_week, opens_at, closes_at FROM branch_booking_hours
                WHERE branch_id = ?
                """, (rs, row) -> Map.entry(DayOfWeek.of(rs.getInt("day_of_week")),
                new ScheduleHours(rs.getTime("opens_at").toLocalTime(),
                        rs.getTime("closes_at").toLocalTime())), branchId)
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
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
            if (requiredTypes.contains(type) && !isOperationallyUsable(machine)) {
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

    private Map<UUID, List<OccupiedInterval>> occupiedIntervals(UUID branchId,
                                                                 ZoneId branchZone,
                                                                 Instant from,
                                                                 Instant until,
                                                                 Map<UUID, Resource> resourcesById,
                                                                 int bufferMinutes) {
        Map<UUID, List<OccupiedInterval>> result = new java.util.HashMap<>();
        if (resourcesById.isEmpty()) return result;
        Instant now = Instant.now();
        resourcesById.values().forEach(resource -> activeSession(resource, bufferMinutes, now)
                .ifPresent(session -> result.computeIfAbsent(resource.id(), ignored -> new ArrayList<>())
                        .add(session)));
        jdbc.query("""
                SELECT a.resource_id, a.occupied_from, a.occupied_until
                FROM booking_resource_allocations a
                JOIN scheduled_bookings b ON b.id = a.booking_id
                WHERE b.branch_id = ?
                  AND a.occupied_from < ?
                  AND a.occupied_until > ?
                  AND (b.status <> 'HELD' OR b.hold_expires_at > NOW())
                  AND b.status NOT IN ('CANCELLED', 'EXPIRED', 'COMPLETED', 'PAYMENT_REVIEW_REQUIRED')
                """, rs -> {
            UUID resourceId = rs.getObject("resource_id", UUID.class);
            if (resourcesById.containsKey(resourceId)) {
                result.computeIfAbsent(resourceId, ignored -> new ArrayList<>())
                        .add(new OccupiedInterval(rs.getTimestamp("occupied_from").toInstant(),
                                rs.getTimestamp("occupied_until").toInstant()));
            }
        }, branchId, OffsetDateTime.ofInstant(until, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(from, ZoneOffset.UTC));

        LocalDateTime legacyFrom = LocalDateTime.ofInstant(from, branchZone);
        LocalDateTime legacyUntil = LocalDateTime.ofInstant(until, branchZone);
        jdbc.query("""
                SELECT mr.machine_id, mr.start_time, mr.end_time, mr.status
                FROM machine_reservations mr
                JOIN machines m ON m.id = mr.machine_id
                WHERE m.branch_id = ?
                  AND mr.status IN ('RESERVED', 'CONFIRMED', 'ACTIVE')
                  AND mr.start_time < ?
                  AND (mr.end_time IS NULL OR mr.end_time > ?)
                """, rs -> {
            UUID resourceId = rs.getObject("machine_id", UUID.class);
            if (!resourcesById.containsKey(resourceId)) return;
            LocalDateTime localStart = rs.getObject("start_time", LocalDateTime.class);
            LocalDateTime localEnd = rs.getObject("end_time", LocalDateTime.class);
            Instant occupiedStart = localStart.atZone(branchZone).toInstant();
            Instant occupiedEnd = localEnd == null
                    ? ("ACTIVE".equals(rs.getString("status"))
                    ? until : localStart.plusHours(2).atZone(branchZone).toInstant())
                    : localEnd.atZone(branchZone).toInstant();
            result.computeIfAbsent(resourceId, ignored -> new ArrayList<>())
                    .add(new OccupiedInterval(occupiedStart, occupiedEnd));
        }, branchId, legacyUntil, legacyFrom);
        return result;
    }

    static boolean hasAvailableResourceCombination(List<AllocationPlan> allocations,
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

    static List<AvailableStartRange> toStartRanges(List<Instant> availableStarts) {
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

    private int availableResourceCount(UUID branchId, String type) {
        if ("WASHER".equals(type) || "DRYER".equals(type)) {
            MachineType machineType = MachineType.valueOf(type);
            return (int) machineRepository.findByBranchId(branchId).stream()
                    .filter(machine -> machine.getType() == machineType && isOperationallyUsable(machine))
                    .count();
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM branch_service_resources
                WHERE branch_id = ? AND resource_type = ? AND enabled = TRUE
                """, Integer.class, branchId, type);
        return count == null ? 0 : count;
    }

    private boolean isWithinOpeningHours(ServiceConfig config, Instant start, SlotPlan plan) {
        ZoneId zone = ZoneId.of(config.timezone());
        Instant bookingEnd = "ASSISTED_LOCKER".equals(config.mode())
                ? plan.checkInEnd() : plan.expectedEnd();
        return !start.isBefore(Instant.now())
                && isCoveredByOpeningHours(scheduleHours(config.branchId()), zone, start, bookingEnd);
    }

    static boolean isCoveredByOpeningHours(Map<DayOfWeek, ScheduleHours> weeklyHours,
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

    private static Instant closingTime(LocalDate openingDate, ScheduleHours hours, ZoneId zone) {
        if (hours.closesAt().equals(LocalTime.of(23, 59))) {
            return openingDate.plusDays(1).atStartOfDay(zone).toInstant();
        }
        LocalDateTime closesLocal = openingDate.atTime(hours.closesAt());
        if (!hours.closesAt().isAfter(hours.opensAt())) {
            closesLocal = closesLocal.plusDays(1);
        }
        return closesLocal.atZone(zone).toInstant();
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

    static SlotPlan buildPlan(ServiceConfig config, Instant start) {
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

    private static int offsetMinutes(Instant instant) {
        return Math.toIntExact(instant.getEpochSecond() / 60);
    }

    static boolean intervalsOverlap(Instant requestedStart,
                                    Instant requestedEnd,
                                    Instant existingStart,
                                    Instant existingEnd) {
        return existingStart.isBefore(requestedEnd) && existingEnd.isAfter(requestedStart);
    }

    static boolean isMinuteAligned(Instant instant) {
        return instant.getNano() == 0 && Math.floorMod(instant.getEpochSecond(), 60) == 0;
    }

    private List<Resource> resourcesFor(UUID branchId, String type) {
        if ("WASHER".equals(type) || "DRYER".equals(type)) {
            MachineType machineType = MachineType.valueOf(type);
            return machineRepository.findByBranchId(branchId).stream()
                    .filter(machine -> machine.getType() == machineType && isOperationallyUsable(machine))
                    .sorted(Comparator.comparing(Machine::getMachineCode))
                    .map(machine -> new Resource(type, machine.getId(), machine.getMachineCode(),
                            machine.getDisplayName() == null || machine.getDisplayName().isBlank()
                                    ? machine.getMachineCode() : machine.getDisplayName(),
                            machine.getLocationDescription(), machine.getStatus().name()))
                    .toList();
        }
        // A compartment fitted to a smart locker is only bookable while its cabinet
        // is usable (online, commissioned); unlinked compartments are managed by hand.
        Set<UUID> usableCabinets = machineRepository.findByBranchId(branchId).stream()
                .filter(machine -> machine.getType() == MachineType.LOCKER && isOperationallyUsable(machine))
                .map(Machine::getId)
                .collect(java.util.stream.Collectors.toSet());
        return jdbc.query("""
                SELECT id, resource_code, display_name, locker_machine_id FROM branch_service_resources
                WHERE branch_id = ? AND resource_type = ? AND enabled = TRUE
                ORDER BY resource_code
                """, (rs, row) -> {
                    UUID cabinet = rs.getObject("locker_machine_id", UUID.class);
                    return cabinet == null || usableCabinets.contains(cabinet)
                            ? new Resource(type, rs.getObject("id", UUID.class), rs.getString("resource_code"),
                                    rs.getString("display_name"), null, "ENABLED")
                            : null;
                }, branchId, type).stream().filter(java.util.Objects::nonNull).toList();
    }

    private boolean resourceBusy(Resource resource,
                                 AllocationPlan plan,
                                 UUID excludeBookingId,
                                 UUID excludeTransactionId) {
        return resourceBusy(resource, plan, excludeBookingId, excludeTransactionId, 0);
    }

    /**
     * True when {@code plan} overlaps a live booking/hold allocation, a legacy
     * machine reservation, or the machine's current session (running cycle).
     */
    private boolean resourceBusy(Resource resource,
                                 AllocationPlan plan,
                                 UUID excludeBookingId,
                                 UUID excludeTransactionId,
                                 int bufferMinutes) {
        boolean sessionConflict = activeSession(resource, bufferMinutes, Instant.now())
                .filter(session -> intervalsOverlap(plan.from(), plan.until(), session.start(), session.end()))
                .isPresent();
        if (sessionConflict) {
            return true;
        }
        Integer scheduledCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM booking_resource_allocations a
                JOIN scheduled_bookings b ON b.id = a.booking_id
                WHERE a.resource_id = ?
                  AND a.occupied_from < ?
                  AND a.occupied_until > ?
                  AND (?::uuid IS NULL OR b.id <> ?)
                  AND (b.status <> 'HELD' OR b.hold_expires_at > NOW())
                  AND b.status NOT IN ('CANCELLED', 'EXPIRED', 'COMPLETED', 'PAYMENT_REVIEW_REQUIRED')
                """, Integer.class, resource.id(),
                OffsetDateTime.ofInstant(plan.until(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(plan.from(), ZoneOffset.UTC),
                excludeBookingId, excludeBookingId);
        if (scheduledCount != null && scheduledCount > 0) {
            return true;
        }
        if (!"WASHER".equals(resource.type()) && !"DRYER".equals(resource.type())) {
            return false;
        }
        LocalDateTime from = LocalDateTime.ofInstant(plan.from(), ZoneId.systemDefault());
        LocalDateTime until = LocalDateTime.ofInstant(plan.until(), ZoneId.systemDefault());
        return machineReservationRepository.findOverlappingReservations(resource.id(), from, until).stream()
                .filter(reservation -> excludeTransactionId == null || reservation.getTransaction() == null
                        || !excludeTransactionId.equals(reservation.getTransaction().getId()))
                .findAny().isPresent();
    }

    /**
     * Machines that can take a booking: online, commissioned and either idle or
     * in a session that will end (that session is then treated as occupied,
     * see {@link #activeSession}). ERROR / OUT_OF_SERVICE / offline are excluded.
     */
    private boolean isOperationallyUsable(Machine machine) {
        return machine.isOnline() && machine.isCommissioned()
                && (machine.getStatus() == MachineStatus.AVAILABLE
                || machine.getStatus() == MachineStatus.RESERVED
                || SESSION_STATUSES.contains(machine.getStatus()));
    }

    /**
     * The interval a machine is busy because of what it is doing right now:
     * remaining cycle time reported by the device (or a configured fallback)
     * plus the turnover buffer. Empty for idle machines and non-machine resources.
     */
    Optional<OccupiedInterval> activeSession(Resource resource, int bufferMinutes, Instant now) {
        if (resource.status() == null
                || (!"WASHER".equals(resource.type()) && !"DRYER".equals(resource.type()))) {
            return Optional.empty();
        }
        MachineStatus status;
        try {
            status = MachineStatus.valueOf(resource.status());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!SESSION_STATUSES.contains(status)) {
            return Optional.empty();
        }
        long remainingSeconds = switch (status) {
            case RUNNING, PAUSED -> runtimeStateService.get(resource.id())
                    .map(com.laundry.machine.runtime.MachineRuntimeState::getRemainingSeconds)
                    .filter(seconds -> seconds != null && seconds > 0)
                    .map(Integer::longValue)
                    .orElse(activeSessionFallbackMinutes * 60);
            // Waiting for START is covered by its reservation; a finished cycle only needs unloading.
            default -> 0L;
        };
        Instant end = now.plusSeconds(remainingSeconds + Math.max(0, bufferMinutes) * 60L);
        // Round up to a whole minute so it lines up with minute-precision start times.
        long epochMinute = (end.getEpochSecond() + 59) / 60;
        end = Instant.ofEpochSecond(epochMinute * 60);
        return end.isAfter(now) ? Optional.of(new OccupiedInterval(now, end)) : Optional.empty();
    }

    private void validateSelectedResources(UUID branchId, List<Resource> resources) {
        for (Resource resource : resources) {
            if (!"WASHER".equals(resource.type()) && !"DRYER".equals(resource.type())) continue;
            List<Boolean> valid = jdbc.query("""
                    SELECT branch_id = ? AND type = ? AND online = TRUE
                           AND deployment_status = 'ACTIVE'
                           AND status IN ('AVAILABLE', 'RESERVED', 'WAITING_START',
                                          'RUNNING', 'PAUSED', 'COMPLETED')
                    FROM machines WHERE id = ? FOR UPDATE
                    """, (rs, row) -> rs.getBoolean(1), branchId, resource.type(), resource.id());
            if (valid.isEmpty() || !valid.getFirst()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A selected machine is no longer available for this branch or service");
            }
        }
    }

    private void lockResources(List<Resource> resources) {
        resources.stream().distinct()
                .sorted(Comparator.comparing(Resource::type).thenComparing(r -> r.id().toString()))
                .forEach(resource -> {
                    if ("WASHER".equals(resource.type()) || "DRYER".equals(resource.type())) {
                        machineRepository.findByIdForReservation(resource.id())
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                                        "A selected machine is no longer available"));
                    } else {
                        List<UUID> locked = jdbc.query("""
                                SELECT id FROM branch_service_resources
                                WHERE id = ? AND enabled = TRUE FOR UPDATE
                                """, (rs, row) -> rs.getObject(1, UUID.class), resource.id());
                        if (locked.isEmpty()) {
                            throw new ResponseStatusException(HttpStatus.CONFLICT,
                                    "A selected resource is no longer available");
                        }
                    }
                });
    }
}
