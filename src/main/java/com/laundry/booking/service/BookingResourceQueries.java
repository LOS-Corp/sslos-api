package com.laundry.booking.service;

import com.laundry.shared.error.ErrorCode;

import com.laundry.shared.error.BusinessException;

import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import com.laundry.booking.domain.LoadPolicy;

import static com.laundry.booking.dto.BookingDtos.*;

import com.laundry.booking.domain.SlotPlanner.*;
import static com.laundry.booking.domain.SlotPlanner.*;

/**
 * Read side shared by availability, holds and configuration: store offerings, opening hours,
 * bookable resources, running sessions and overlap checks (FR-RES-07, BR-28, BR-31).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingResourceQueries {
    private final JdbcTemplate jdbc;
    private final MachineRepository machineRepository;
    private final com.laundry.machine.runtime.MachineRuntimeStateService runtimeStateService;
    private final com.laundry.config.BookingProperties props;

    /** Machine states that mean "in use right now" but bookable once the session ends. */
    static final Set<MachineStatus> SESSION_STATUSES = java.util.EnumSet.of(
            MachineStatus.WAITING_START, MachineStatus.RUNNING,
            MachineStatus.PAUSED, MachineStatus.COMPLETED);


    /**
     * V70: self-service takes the store's check-in, check-out and (two-machine services) transfer minutes; assisted
     * laundry keeps the service's own. Needs the aliases {@code d} (booking_service_definitions) and {@code s}
     * (store_settings).
     */
    static final String STAGE_MINUTES_SQL = """
            CASE WHEN d.service_mode = 'SELF_SERVICE' THEN s.check_in_minutes ELSE d.check_in_minutes END check_in_minutes,
            CASE WHEN d.service_mode = 'SELF_SERVICE' THEN s.checkout_minutes ELSE d.checkout_minutes END checkout_minutes,
            CASE WHEN d.service_mode = 'SELF_SERVICE' AND EXISTS (
                     SELECT 1 FROM booking_service_requirements r2 WHERE r2.service_code = d.code AND r2.sequence_no = 2)
                 THEN s.transfer_minutes ELSE d.transfer_minutes END transfer_minutes""";

    ServiceConfig serviceDefinition(String code) {
        List<ServiceConfig> definitions = jdbc.query("""
                SELECT d.code, d.name, d.service_mode, d.description, d.processing_minutes,
                       d.secondary_processing_minutes, d.pickup_deadline_hours,
                """ + STAGE_MINUTES_SQL + """

                FROM booking_service_definitions d CROSS JOIN store_settings s
                WHERE d.code = ? AND d.active = TRUE
                """, (rs, row) -> new ServiceConfig(
                rs.getString("code"), rs.getString("name"), rs.getString("service_mode"),
                rs.getString("description"), rs.getInt("processing_minutes"),
                rs.getInt("secondary_processing_minutes"), rs.getInt("check_in_minutes"),
                rs.getInt("checkout_minutes"), rs.getInt("transfer_minutes"),
                rs.getInt("pickup_deadline_hours"), null, null, null, null,
                requirementsFor(rs.getString("code")), 0), code);
        if (definitions.isEmpty()) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Service not found");
        }
        return definitions.getFirst();
    }

    ServiceConfig offering(String serviceCode) {
        ServiceConfig definition = serviceDefinition(serviceCode);
        List<ServiceConfig> configured = jdbc.query("""
                SELECT o.price, COALESCE(o.processing_minutes_override, d.processing_minutes) processing_minutes,
                       COALESCE(o.secondary_processing_minutes_override, d.secondary_processing_minutes) secondary_processing_minutes,
                       s.timezone, s.turnover_buffer_minutes
                FROM service_offerings o
                JOIN booking_service_definitions d ON d.code = o.service_code
                CROSS JOIN store_settings s
                WHERE o.service_code = ? AND o.enabled = TRUE AND d.active = TRUE AND s.timezone IS NOT NULL
                """, (rs, row) -> new ServiceConfig(
                definition.code(), definition.name(), definition.mode(), definition.description(),
                rs.getInt("processing_minutes"), rs.getInt("secondary_processing_minutes"),
                definition.checkInMinutes(), definition.checkoutMinutes(), definition.transferMinutes(),
                definition.pickupDeadlineHours(), rs.getBigDecimal("price"),
                rs.getString("timezone"), null, null,
                definition.requirements(), rs.getInt("turnover_buffer_minutes")), serviceCode);
        if (configured.isEmpty()) {
            return null;
        }
        ServiceConfig config = configured.getFirst();
        // V68: a machine-priced service shows its cheapest booking ("from" price) instead of the offering's price.
        if (!pricedByMachine(config.mode(), config.requirements())) {
            return config;
        }
        return new ServiceConfig(config.code(), config.name(), config.mode(), config.description(),
                config.processingMinutes(), config.secondaryProcessingMinutes(), config.checkInMinutes(),
                config.checkoutMinutes(), config.transferMinutes(), config.pickupDeadlineHours(),
                startingPrice(config.requirements()), config.timezone(), config.opensAt(), config.closesAt(),
                config.requirements(), config.bufferMinutes());
    }

    /**
     * The store offering as a customer books it. V69: the machines' price profiles set the durations (the washer's
     * and the dryer's stage each take the longest cycle among the commissioned machines of that type, so a booking
     * always covers the machine it gets); a type without any keeps the service's own duration. The price is what is
     * paid when booking (V68: from the cheapest machine; assisted laundry its fixed part). {@code loadKg} no longer
     * changes the price.
     */
    ServiceConfig customerOffering(String serviceCode, BigDecimal loadKg) {
        ServiceConfig config = offering(serviceCode);
        if (config == null) {
            return null;
        }
        int primary = config.processingMinutes();
        int secondary = config.secondaryProcessingMinutes();
        List<String> requirements = config.requirements();
        for (int i = 0; i < requirements.size() && i < 2; i++) {
            Integer minutes = longestCycle(requirements.get(i));
            if (minutes == null) continue;
            if (i == 0) primary = minutes;
            else secondary = minutes;
        }
        return new ServiceConfig(config.code(), config.name(), config.mode(), config.description(), primary, secondary,
                config.checkInMinutes(), config.checkoutMinutes(), config.transferMinutes(),
                config.pickupDeadlineHours(), config.price(), config.timezone(), config.opensAt(), config.closesAt(),
                config.requirements(), config.bufferMinutes());
    }

    /** Cycle minutes of each priced washer / dryer (V69: its price profile), by machine id. */
    Map<UUID, Integer> machineCycles() {
        Map<UUID, Integer> cycles = new java.util.HashMap<>();
        jdbc.query("""
                SELECT m.id, p.cycle_minutes FROM machines m JOIN price_profiles p ON p.id = m.price_profile_id
                """, rs -> { cycles.put(rs.getObject(1, UUID.class), rs.getInt(2)); });
        return cycles;
    }

    /**
     * {@code config} with its stages sized for one machine per requirement (V71): stage {@code i} runs for
     * {@code minutes.get(i)}; a null entry (a resource that is not a priced machine) keeps the service's duration.
     */
    static ServiceConfig withStageMinutes(ServiceConfig config, List<Integer> minutes) {
        int primary = !minutes.isEmpty() && minutes.get(0) != null ? minutes.get(0) : config.processingMinutes();
        int secondary = minutes.size() > 1 && minutes.get(1) != null ? minutes.get(1) : config.secondaryProcessingMinutes();
        return new ServiceConfig(config.code(), config.name(), config.mode(), config.description(), primary, secondary,
                config.checkInMinutes(), config.checkoutMinutes(), config.transferMinutes(),
                config.pickupDeadlineHours(), config.price(), config.timezone(), config.opensAt(), config.closesAt(),
                config.requirements(), config.bufferMinutes());
    }

    /** {@code config} sized for exactly these resources, in requirement order: each machine holds its own cycle. */
    ServiceConfig forResources(ServiceConfig config, List<Resource> resources) {
        Map<UUID, Integer> cycles = machineCycles();
        return withStageMinutes(config, resources.stream().map(resource -> cycles.get(resource.id())).toList());
    }

    /** Longest cycle of the commissioned, priced machines of {@code type}; null for other resources or none. */
    Integer longestCycle(String type) {
        if (!"WASHER".equals(type) && !"DRYER".equals(type)) {
            return null;
        }
        return jdbc.queryForObject("""
                SELECT MAX(p.cycle_minutes) FROM machines m
                JOIN price_profiles p ON p.id = m.price_profile_id
                WHERE m.type = ? AND m.deployment_status = 'ACTIVE'
                """, Integer.class, type);
    }

    /**
     * What a booking of {@code config} on these resources costs.
     *
     * @param total      the amount to pay when booking
     * @param pricePerKg assisted laundry (V65): the per-kg price charged once staff weighed the laundry, recorded on
     *                   the booking; null for self-service
     */
    record Pricing(BigDecimal total, BigDecimal pricePerKg) {}

    /**
     * V68: self-service on washers and dryers only is priced by the machines' price profiles, never by the service
     * offering (which keeps pricing assisted laundry and services on other resources, e.g. iron stations).
     */
    static boolean pricedByMachine(String mode, List<String> requirements) {
        return "SELF_SERVICE".equals(mode) && !requirements.isEmpty()
                && requirements.stream().allMatch(type -> "WASHER".equals(type) || "DRYER".equals(type));
    }

    /**
     * Cheapest booking of a machine-priced service ("from" price): the cheapest priced, commissioned machine of each
     * required type. Null when a required type has no such machine: the service cannot be booked.
     */
    BigDecimal startingPrice(List<String> requirements) {
        BigDecimal total = BigDecimal.ZERO;
        for (String type : requirements) {
            BigDecimal cheapest = jdbc.queryForObject("""
                    SELECT MIN(p.base_price) FROM machines m
                    JOIN price_profiles p ON p.id = m.price_profile_id
                    WHERE m.type = ? AND m.deployment_status = 'ACTIVE'
                    """, BigDecimal.class, type);
            if (cheapest == null) {
                return null;
            }
            total = total.add(cheapest);
        }
        return total;
    }

    /**
     * Prices a booking. Self-service on washers/dryers costs the sum of the chosen machines' price profiles (V68: every
     * bookable machine has one, see {@link #resourcesFor}). Assisted laundry pays the offering's price when booking and
     * its per-kg price after weighing; other services the offering's price.
     */
    Pricing price(ServiceConfig config, List<UUID> resourceIds, BigDecimal loadKg) {
        if ("ASSISTED_LOCKER".equals(config.mode())) {
            return new Pricing(config.price(), pricePerKg(config.code()));
        }
        if (!pricedByMachine(config.mode(), config.requirements())) {
            return new Pricing(config.price(), null);
        }
        List<BigDecimal> profilePrices = resourceIds.isEmpty() ? List.of() : jdbc.query("""
                SELECT p.base_price FROM machines m
                JOIN price_profiles p ON p.id = m.price_profile_id
                WHERE m.id = ANY (?)
                """, (rs, row) -> rs.getBigDecimal(1), (Object) resourceIds.toArray(new UUID[0]));
        if (resourceIds.isEmpty() || profilePrices.size() != resourceIds.size()) {
            throw BusinessException.of(ErrorCode.CONFIG_CONFLICT, "Máy chưa có profile giá nên chưa nhận đặt lịch");
        }
        return new Pricing(profilePrices.stream().reduce(BigDecimal.ZERO, BigDecimal::add), null);
    }

    /**
     * Price per kilogram of an assisted laundry service (V65), charged once staff weighed the laundry; null for
     * self-service, which is priced per cycle.
     */
    BigDecimal pricePerKg(String serviceCode) {
        return jdbc.query("""
                SELECT o.price_per_kg FROM service_offerings o
                JOIN booking_service_definitions d ON d.code = o.service_code
                WHERE o.service_code = ? AND d.service_mode = 'ASSISTED_LOCKER'
                """, (rs, row) -> rs.getBigDecimal(1), serviceCode).stream()
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    List<String> requirementsFor(String serviceCode) {
        return jdbc.query("""
                SELECT resource_type FROM booking_service_requirements
                WHERE service_code = ? ORDER BY sequence_no
                """, (rs, row) -> rs.getString(1), serviceCode);
    }

    boolean hasConfiguredHours() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM store_hours", Integer.class);
        return count != null && count > 0;
    }

    void lockStoreConfiguration() {
        // One row: serialises configuration changes and holds against each other.
        List<Short> locked = jdbc.query("SELECT id FROM store_settings FOR UPDATE",
                (rs, row) -> rs.getShort(1));
        if (locked.isEmpty()) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Store settings are missing");
        }
    }

    Map<DayOfWeek, ScheduleHours> scheduleHours() {
        return jdbc.query("""
                SELECT day_of_week, opens_at, closes_at FROM store_hours
                """, (rs, row) -> Map.entry(DayOfWeek.of(rs.getInt("day_of_week")),
                new ScheduleHours(rs.getTime("opens_at").toLocalTime(),
                        rs.getTime("closes_at").toLocalTime())))
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    List<OpeningHoursDto> openingHours() {
        return jdbc.query("""
                SELECT day_of_week, opens_at, closes_at
                FROM store_hours
                ORDER BY day_of_week
                """, (rs, row) -> new OpeningHoursDto(
                DayOfWeek.of(rs.getInt("day_of_week")).name(),
                rs.getTime("opens_at").toLocalTime(),
                rs.getTime("closes_at").toLocalTime()));
    }

    /**
     * Live allocations of the given resources overlapping {@code [from, until)}. The predicate is written to
     * match the V44 no-overlap index ({@code gist (resource_id, tstzrange(occupied_from, occupied_until, '[)'))
     * WHERE active}), so finished history is never read: the cost follows the live bookings, not the table size.
     * {@code active} is kept equal to {@code booking_holds_resources(status)} by the V44 trigger; holds whose
     * time ran out but that the expiry job has not swept yet are skipped through the booking.
     */
    private static final String LIVE_ALLOCATIONS_SQL = """
            SELECT %s
            FROM booking_resource_allocations a
            JOIN scheduled_bookings b ON b.id = a.booking_id
            WHERE a.active
              AND a.resource_id = ANY (?)
              AND tstzrange(a.occupied_from, a.occupied_until, '[)') && tstzrange(?, ?, '[)')
              AND (b.status <> 'HELD' OR b.hold_expires_at > NOW())
            """;

    Map<UUID, List<OccupiedInterval>> occupiedIntervals(
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
        jdbc.query(LIVE_ALLOCATIONS_SQL.formatted("a.resource_id, a.occupied_from, a.occupied_until"), rs -> {
            UUID resourceId = rs.getObject("resource_id", UUID.class);
            result.computeIfAbsent(resourceId, ignored -> new ArrayList<>())
                    .add(new OccupiedInterval(rs.getTimestamp("occupied_from").toInstant(),
                            rs.getTimestamp("occupied_until").toInstant()));
        }, resourcesById.keySet().toArray(UUID[]::new),
                OffsetDateTime.ofInstant(from, ZoneOffset.UTC), OffsetDateTime.ofInstant(until, ZoneOffset.UTC));
        return result;
    }

    int availableResourceCount(String type) {
        if ("WASHER".equals(type) || "DRYER".equals(type)) {
            MachineType machineType = MachineType.valueOf(type);
            return (int) machineRepository.findAll().stream()
                    .filter(machine -> machine.getType() == machineType && isOperationallyUsable(machine))
                    .count();
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM service_resources WHERE resource_type = ? AND enabled = TRUE
                """, Integer.class, type);
        return count == null ? 0 : count;
    }

    boolean isWithinOpeningHours(ServiceConfig config, Instant start, SlotPlan plan) {
        ZoneId zone = ZoneId.of(config.timezone());
        Instant bookingEnd = "ASSISTED_LOCKER".equals(config.mode())
                ? plan.checkInEnd() : plan.expectedEnd();
        return !start.isBefore(Instant.now())
                && isCoveredByOpeningHours(scheduleHours(), zone, start, bookingEnd);
    }

    List<Resource> resourcesFor(String type) {
        return resourcesFor(type, machineRepository.findAll());
    }

    /** Bookable resources of each type, from one load of the store's machines. */
    Map<String, List<Resource>> resourcesByType(List<String> types, List<Machine> machines) {
        Map<String, List<Resource>> result = new java.util.LinkedHashMap<>();
        types.forEach(type -> result.put(type, resourcesFor(type, machines)));
        return result;
    }

    /** Same as {@link #resourcesFor(String)} with the machines already loaded by the caller. */
    List<Resource> resourcesFor(String type, List<Machine> machines) {
        if ("WASHER".equals(type) || "DRYER".equals(type)) {
            MachineType machineType = MachineType.valueOf(type);
            // V68: a washer or dryer is priced by its price profile; without one it cannot be booked.
            return machines.stream()
                    .filter(machine -> machine.getType() == machineType && isOperationallyUsable(machine)
                            && machine.getPriceProfileId() != null)
                    .sorted(Comparator.comparing(Machine::getMachineCode))
                    .map(machine -> new Resource(type, machine.getId(), machine.getMachineCode(),
                            machine.getDisplayName() == null || machine.getDisplayName().isBlank()
                                    ? machine.getMachineCode() : machine.getDisplayName(),
                            machine.getLocationDescription(), machine.getStatus().name()))
                    .toList();
        }
        // A compartment fitted to a smart locker is only bookable while its cabinet
        // is usable (online, commissioned); unlinked compartments are managed by hand.
        Set<UUID> usableCabinets = machines.stream()
                .filter(machine -> machine.getType() == MachineType.LOCKER && isOperationallyUsable(machine))
                .map(Machine::getId)
                .collect(java.util.stream.Collectors.toSet());
        return jdbc.query("""
                SELECT id, resource_code, display_name, locker_machine_id FROM service_resources
                WHERE resource_type = ? AND enabled = TRUE
                ORDER BY resource_code
                """, (rs, row) -> {
                    UUID cabinet = rs.getObject("locker_machine_id", UUID.class);
                    return cabinet == null || usableCabinets.contains(cabinet)
                            ? new Resource(type, rs.getObject("id", UUID.class), rs.getString("resource_code"),
                                    rs.getString("display_name"), null, "ENABLED")
                            : null;
                }, type).stream().filter(java.util.Objects::nonNull).toList();
    }

    /**
     * True when {@code plan} overlaps a live booking/hold allocation or the machine's
     * current session (running cycle).
     */
    boolean resourceBusy(Resource resource, AllocationPlan plan, int bufferMinutes) {
        return busyResources(List.of(resource), plan, bufferMinutes).contains(resource.id());
    }

    /**
     * The resources among {@code resources} that cannot take {@code plan}: a running session overlaps it
     * (in memory) or a live booking/hold allocation does. One SQL statement for the whole list, so the
     * recommendation path does not issue a query per machine.
     */
    Set<UUID> busyResources(Collection<Resource> resources, AllocationPlan plan, int bufferMinutes) {
        Set<UUID> busy = new HashSet<>();
        if (resources.isEmpty()) {
            return busy;
        }
        Instant now = Instant.now();
        for (Resource resource : resources) {
            activeSession(resource, bufferMinutes, now)
                    .filter(session -> intervalsOverlap(plan.from(), plan.until(), session.start(), session.end()))
                    .ifPresent(session -> busy.add(resource.id()));
        }
        UUID[] ids = resources.stream().map(Resource::id).distinct().toArray(UUID[]::new);
        busy.addAll(jdbc.queryForList(LIVE_ALLOCATIONS_SQL.formatted("DISTINCT a.resource_id"), UUID.class, ids,
                OffsetDateTime.ofInstant(plan.from(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(plan.until(), ZoneOffset.UTC)));
        return busy;
    }

    /**
     * Machines that can take a booking: online, commissioned and either idle or
     * in a session that will end (that session is then treated as occupied,
     * see {@link #activeSession}). ERROR / OUT_OF_SERVICE / offline are excluded.
     */
    boolean isOperationallyUsable(Machine machine) {
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
                    .orElse(props.activeSessionFallbackMinutes() * 60);
            // Waiting for START is covered by its reservation; a finished cycle only needs unloading.
            default -> 0L;
        };
        Instant end = now.plusSeconds(remainingSeconds + Math.max(0, bufferMinutes) * 60L);
        // Round up to a whole minute so it lines up with minute-precision start times.
        long epochMinute = (end.getEpochSecond() + 59) / 60;
        end = Instant.ofEpochSecond(epochMinute * 60);
        return end.isAfter(now) ? Optional.of(new OccupiedInterval(now, end)) : Optional.empty();
    }
}
