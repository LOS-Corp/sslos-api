package com.laundry.booking.service;

import com.laundry.shared.error.ErrorCode;

import com.laundry.shared.error.BusinessException;

import com.laundry.branch.entity.Branch;
import com.laundry.branch.repository.BranchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.laundry.booking.dto.BookingDtos.*;
import static com.laundry.booking.dto.BookingConfigDtos.*;

import com.laundry.booking.domain.SlotPlanner.*;
import static com.laundry.booking.domain.SlotPlanner.*;

/**
 * Branch booking configuration (UC-12): opening hours, offered services and prices, resources and
 * settings. A change that conflicts with future bookings is refused with 409 (BR-28, MSG23).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BranchBookingConfigService {
    private final JdbcTemplate jdbc;
    private final BranchRepository branchRepository;
    private final BookingResourceQueries queries;

    @Transactional
    public void configureHours(UUID branchId, BookingHoursRequest request) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Branch not found"));
        queries.lockBranchConfiguration(branchId);
        ZoneId zone;
        try {
            zone = ZoneId.of(request.timezone());
        } catch (RuntimeException e) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Invalid IANA timezone");
        }
        if (request.days().stream().map(DayHours::dayOfWeek).distinct().count() != request.days().size()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Duplicate dayOfWeek");
        }
        validateScheduleImpact(branchId, request);
        jdbc.update("""
                INSERT INTO branch_booking_settings(branch_id, timezone) VALUES (?, ?)
                ON CONFLICT (branch_id) DO UPDATE SET timezone = EXCLUDED.timezone
                """, branchId, zone.getId());
        jdbc.update("DELETE FROM branch_booking_hours WHERE branch_id = ?", branchId);
        request.days().forEach(day -> {
            if (day.opensAt().equals(day.closesAt())) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Opening and closing times cannot be identical");
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
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Branch not found"));
        queries.lockBranchConfiguration(branchId);
        queries.serviceDefinition(serviceCode);
        List<Boolean> existing = jdbc.query("""
                SELECT enabled FROM branch_service_offerings
                WHERE branch_id = ? AND service_code = ?
                """, (rs, row) -> rs.getBoolean(1), branchId, serviceCode);
        ServiceConfig existingConfig = queries.offering(branchId, serviceCode);
        boolean currentlyEnabled = !existing.isEmpty() && existing.getFirst();
        boolean durationChanged = existingConfig != null
                && ((request.processingMinutes() != null
                && request.processingMinutes() != existingConfig.processingMinutes())
                || (request.secondaryProcessingMinutes() != null
                && request.secondaryProcessingMinutes() != existingConfig.secondaryProcessingMinutes()));
        if (hasFutureReservations(branchId, serviceCode)
                && ((currentlyEnabled && !request.enabled()) || durationChanged)) {
            throw BusinessException.of(ErrorCode.CONFIG_CONFLICT, "Cannot disable or change service duration while future bookings exist");
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
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Branch not found"));
        queries.lockBranchConfiguration(branchId);
        String resourceType = request.resourceType().trim().toUpperCase();
        if (!Set.of("IRON_STATION", "LOCKER").contains(resourceType)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "resourceType must be IRON_STATION or LOCKER");
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
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Branch not found"));
        return queries.openingHours(branchId);
    }

    @Transactional
    public void setResourceEnabled(UUID branchId, UUID resourceId, boolean enabled) {
        queries.lockBranchConfiguration(branchId);
        if (!enabled && hasFutureResourceAllocations(resourceId)) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Cannot disable a resource with future holds or confirmed bookings");
        }
        int changed = jdbc.update("""
                UPDATE branch_service_resources SET enabled = ?
                WHERE branch_id = ? AND id = ?
                """, enabled, branchId, resourceId);
        if (changed == 0) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Branch resource not found");
        }
    }

    @Transactional(readOnly = true)
    public BookingSettingsDto getBookingSettings(UUID branchId) {
        branchRepository.findById(branchId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Branch not found"));
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
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Branch not found"));
        queries.lockBranchConfiguration(branchId);
        ZoneId zone;
        try {
            zone = ZoneId.of(request.timezone());
        } catch (RuntimeException exception) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Invalid IANA timezone");
        }
        String currentTimezone = jdbc.query("""
                SELECT timezone FROM branch_booking_settings WHERE branch_id = ?
                """, rs -> rs.next() ? rs.getString(1) : null, branchId);
        if (currentTimezone != null && !currentTimezone.equals(zone.getId())
                && hasFutureBranchBookings(branchId)) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Cannot change timezone while future holds or confirmed bookings exist");
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
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Branch not found"));
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
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Branch not found"));
        queries.lockBranchConfiguration(branchId);
        if (!request.enabled() && hasFutureResourceAllocations(resourceId)) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Cannot disable a resource with future holds or confirmed bookings");
        }
        int changed = jdbc.update("""
                UPDATE branch_service_resources
                SET resource_code = ?, display_name = ?, enabled = ?
                WHERE branch_id = ? AND id = ?
                """, request.resourceCode().trim(), request.displayName().trim(),
                request.enabled(), branchId, resourceId);
        if (changed == 0) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Branch resource not found");
        }
        return jdbc.queryForObject("""
                SELECT id, branch_id, resource_type, resource_code, display_name, enabled
                FROM branch_service_resources WHERE id = ?
                """, (rs, row) -> new ResourceDto(
                rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                rs.getString("resource_type"), rs.getString("resource_code"),
                rs.getString("display_name"), rs.getBoolean("enabled")), resourceId);
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
                throw BusinessException.of(ErrorCode.CONFIG_CONFLICT, "Updated hours would close during an existing future booking");
            }
        }
    }
}
