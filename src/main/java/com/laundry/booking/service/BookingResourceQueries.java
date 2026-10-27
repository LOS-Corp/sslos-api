package com.laundry.booking.service;

import com.laundry.shared.error.ErrorCode;

import com.laundry.shared.error.BusinessException;

import com.laundry.branch.entity.Branch;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.*;
import static com.laundry.booking.dto.BookingConfigDtos.*;

import com.laundry.booking.domain.SlotPlanner.*;
import static com.laundry.booking.domain.SlotPlanner.*;

/**
 * Read side shared by availability, holds and configuration: branch offerings, opening hours,
 * bookable resources, running sessions and overlap checks (FR-RES-07, BR-28, BR-31).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingResourceQueries {
    private final JdbcTemplate jdbc;
    private final MachineRepository machineRepository;
    private final com.laundry.machine.runtime.MachineRuntimeStateService runtimeStateService;
    private final MachineProgramService programService;
    private final com.laundry.config.BookingProperties props;

    /** Machine states that mean "in use right now" but bookable once the session ends. */
    static final Set<MachineStatus> SESSION_STATUSES = java.util.EnumSet.of(
            MachineStatus.WAITING_START, MachineStatus.RUNNING,
            MachineStatus.PAUSED, MachineStatus.COMPLETED);


    ServiceConfig serviceDefinition(String code) {
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
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Service not found");
        }
        return definitions.getFirst();
    }

    ServiceConfig offering(UUID branchId, String serviceCode) {
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
    ServiceConfig customerOffering(UUID branchId, String serviceCode, String washProgram, String dryProgram) {
        ServiceConfig config = offering(branchId, serviceCode);
        if (config == null) {
            return null;
        }
        return withPrograms(config, programService.resolve(config.requirements(), washProgram, dryProgram));
    }

    List<String> requirementsFor(String serviceCode) {
        return jdbc.query("""
                SELECT resource_type FROM booking_service_requirements
                WHERE service_code = ? ORDER BY sequence_no
                """, (rs, row) -> rs.getString(1), serviceCode);
    }

    boolean hasConfiguredHours(UUID branchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM branch_booking_hours WHERE branch_id = ?",
                Integer.class, branchId);
        return count != null && count > 0;
    }

    void lockBranchConfiguration(UUID branchId) {
        List<UUID> locked = jdbc.query("""
                SELECT id FROM branches WHERE id = ? FOR UPDATE
                """, (rs, row) -> rs.getObject(1, UUID.class), branchId);
        if (locked.isEmpty()) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Branch not found");
        }
    }

    Map<DayOfWeek, ScheduleHours> scheduleHours(UUID branchId) {
        return jdbc.query("""
                SELECT day_of_week, opens_at, closes_at FROM branch_booking_hours
                WHERE branch_id = ?
                """, (rs, row) -> Map.entry(DayOfWeek.of(rs.getInt("day_of_week")),
                new ScheduleHours(rs.getTime("opens_at").toLocalTime(),
                        rs.getTime("closes_at").toLocalTime())), branchId)
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    List<BranchHoursDto> openingHours(UUID branchId) {
        return jdbc.query("""
                SELECT day_of_week, opens_at, closes_at
                FROM branch_booking_hours WHERE branch_id = ?
                ORDER BY day_of_week
                """, (rs, row) -> new BranchHoursDto(
                DayOfWeek.of(rs.getInt("day_of_week")).name(),
                rs.getTime("opens_at").toLocalTime(),
                rs.getTime("closes_at").toLocalTime()), branchId);
    }

    Map<UUID, List<OccupiedInterval>> occupiedIntervals(UUID branchId,
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
                  AND booking_holds_resources(b.status)
                """, rs -> {
            UUID resourceId = rs.getObject("resource_id", UUID.class);
            if (resourcesById.containsKey(resourceId)) {
                result.computeIfAbsent(resourceId, ignored -> new ArrayList<>())
                        .add(new OccupiedInterval(rs.getTimestamp("occupied_from").toInstant(),
                                rs.getTimestamp("occupied_until").toInstant()));
            }
        }, branchId, OffsetDateTime.ofInstant(until, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(from, ZoneOffset.UTC));
        return result;
    }

    int availableResourceCount(UUID branchId, String type) {
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

    boolean isWithinOpeningHours(ServiceConfig config, Instant start, SlotPlan plan) {
        ZoneId zone = ZoneId.of(config.timezone());
        Instant bookingEnd = "ASSISTED_LOCKER".equals(config.mode())
                ? plan.checkInEnd() : plan.expectedEnd();
        return !start.isBefore(Instant.now())
                && isCoveredByOpeningHours(scheduleHours(config.branchId()), zone, start, bookingEnd);
    }

    List<Resource> resourcesFor(UUID branchId, String type) {
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

    /**
     * True when {@code plan} overlaps a live booking/hold allocation or the machine's
     * current session (running cycle).
     */
    boolean resourceBusy(Resource resource,
                                 AllocationPlan plan,
                                 UUID excludeBookingId,
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
                  AND booking_holds_resources(b.status)
                """, Integer.class, resource.id(),
                OffsetDateTime.ofInstant(plan.until(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(plan.from(), ZoneOffset.UTC),
                excludeBookingId, excludeBookingId);
        return scheduledCount != null && scheduledCount > 0;
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
