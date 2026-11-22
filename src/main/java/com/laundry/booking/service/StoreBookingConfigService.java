package com.laundry.booking.service;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.store.service.StoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.laundry.booking.domain.SlotPlanner.ScheduleHours;
import static com.laundry.booking.domain.SlotPlanner.ServiceConfig;
import static com.laundry.booking.domain.SlotPlanner.isCoveredByOpeningHours;
import static com.laundry.booking.dto.BookingConfigDtos.BookingSettingsDto;
import static com.laundry.booking.dto.BookingConfigDtos.BookingSettingsUpdate;
import static com.laundry.booking.dto.BookingConfigDtos.ResourceUpdate;
import static com.laundry.booking.dto.BookingConfigDtos.ServiceOfferingDto;
import static com.laundry.booking.dto.BookingDtos.BookingHoursRequest;
import static com.laundry.booking.dto.BookingDtos.CreateResourceRequest;
import static com.laundry.booking.dto.BookingDtos.DayHours;
import static com.laundry.booking.dto.BookingDtos.OpeningHoursDto;
import static com.laundry.booking.dto.BookingDtos.ResourceDto;
import static com.laundry.booking.dto.BookingDtos.ServiceOfferingRequest;

/**
 * Booking configuration of the store (UC-12): opening hours, offered services and prices, resources and
 * settings. A change that conflicts with future bookings is refused with 409 (BR-28, MSG23).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StoreBookingConfigService {
    private static final String LIVE_BOOKING = """
            (status IN ('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING', 'READY_FOR_PICKUP')
             OR (status = 'HELD' AND hold_expires_at > NOW()))""";

    private final JdbcTemplate jdbc;
    private final StoreService store;
    private final BookingResourceQueries queries;

    @Transactional
    public void configureHours(BookingHoursRequest request) {
        queries.lockStoreConfiguration();
        ZoneId zone = zone(request.timezone());
        if (request.days().stream().map(DayHours::dayOfWeek).distinct().count() != request.days().size()) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Duplicate dayOfWeek");
        }
        validateScheduleImpact(request);
        jdbc.update("UPDATE store_settings SET timezone = ?, updated_at = NOW()", zone.getId());
        jdbc.update("DELETE FROM store_hours");
        request.days().forEach(day -> {
            if (day.opensAt().equals(day.closesAt())) {
                throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Opening and closing times cannot be identical");
            }
            jdbc.update("INSERT INTO store_hours(day_of_week, opens_at, closes_at) VALUES (?, ?, ?)",
                    day.dayOfWeek().getValue(), day.opensAt(), day.closesAt());
        });
    }

    @Transactional
    public void configureOffering(String serviceCode, ServiceOfferingRequest request) {
        queries.lockStoreConfiguration();
        queries.serviceDefinition(serviceCode);
        List<Boolean> existing = jdbc.query("SELECT enabled FROM service_offerings WHERE service_code = ?",
                (rs, row) -> rs.getBoolean(1), serviceCode);
        ServiceConfig existingConfig = queries.offering(serviceCode);
        boolean currentlyEnabled = !existing.isEmpty() && existing.getFirst();
        boolean durationChanged = existingConfig != null
                && ((request.processingMinutes() != null
                && request.processingMinutes() != existingConfig.processingMinutes())
                || (request.secondaryProcessingMinutes() != null
                && request.secondaryProcessingMinutes() != existingConfig.secondaryProcessingMinutes()));
        if (hasFutureReservations(serviceCode) && ((currentlyEnabled && !request.enabled()) || durationChanged)) {
            throw BusinessException.of(ErrorCode.CONFIG_CONFLICT, "Cannot disable or change service duration while future bookings exist");
        }
        // V65: self-service is priced per cycle (a fixed price above 0). Assisted laundry is priced by weight: a
        // per-kg price charged after staff weighed the laundry, plus the fixed part (≥ 0) paid when booking.
        ServiceConfig definition = queries.serviceDefinition(serviceCode);
        boolean byWeight = "ASSISTED_LOCKER".equals(definition.mode());
        // V68: washers and dryers are priced by their price profiles; the offering keeps no price for them.
        boolean byMachine = BookingResourceQueries.pricedByMachine(definition.mode(), definition.requirements());
        BigDecimal price = byMachine ? BigDecimal.ZERO : request.price();
        if (byWeight && (request.pricePerKg() == null || request.pricePerKg().signum() <= 0)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Giặt hộ tính theo kg: nhập đơn giá/kg lớn hơn 0");
        }
        if (!byMachine && (price == null || price.signum() <= 0)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, byWeight
                    ? "Giặt hộ: nhập phí đặt lịch (trả khi đặt) lớn hơn 0" : "Giá dịch vụ phải lớn hơn 0");
        }
        jdbc.update("""
                INSERT INTO service_offerings
                  (service_code, enabled, price, price_per_kg, processing_minutes_override,
                   secondary_processing_minutes_override)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (service_code) DO UPDATE SET
                  enabled = EXCLUDED.enabled,
                  price = EXCLUDED.price,
                  price_per_kg = EXCLUDED.price_per_kg,
                  processing_minutes_override = EXCLUDED.processing_minutes_override,
                  secondary_processing_minutes_override = EXCLUDED.secondary_processing_minutes_override
                """, serviceCode, request.enabled(), price, byWeight ? request.pricePerKg() : null,
                request.processingMinutes(), request.secondaryProcessingMinutes());
    }

    @Transactional
    public ResourceDto addResource(CreateResourceRequest request) {
        queries.lockStoreConfiguration();
        String resourceType = request.resourceType().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("IRON_STATION", "LOCKER").contains(resourceType)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "resourceType must be IRON_STATION or LOCKER");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO service_resources (id, resource_type, resource_code, display_name, enabled)
                VALUES (?, ?, ?, ?, TRUE)
                """, id, resourceType, request.resourceCode().trim(), request.displayName().trim());
        return new ResourceDto(id, resourceType, request.resourceCode().trim(), request.displayName().trim(), true);
    }

    @Transactional(readOnly = true)
    public List<ResourceDto> listResources() {
        return jdbc.query("""
                SELECT id, resource_type, resource_code, display_name, enabled
                FROM service_resources ORDER BY resource_type, resource_code
                """, (rs, row) -> new ResourceDto(rs.getObject("id", UUID.class),
                rs.getString("resource_type"), rs.getString("resource_code"),
                rs.getString("display_name"), rs.getBoolean("enabled")));
    }

    @Transactional(readOnly = true)
    public List<OpeningHoursDto> getOpeningHours() {
        return queries.openingHours();
    }

    @Transactional
    public void setResourceEnabled(UUID resourceId, boolean enabled) {
        queries.lockStoreConfiguration();
        if (!enabled && hasFutureResourceAllocations(resourceId)) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Cannot disable a resource with future holds or confirmed bookings");
        }
        int changed = jdbc.update("UPDATE service_resources SET enabled = ? WHERE id = ?", enabled, resourceId);
        if (changed == 0) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Resource not found");
        }
    }

    @Transactional
    public BookingSettingsDto getBookingSettings() {
        var settings = store.settings();
        return new BookingSettingsDto(settings.getTimezone(), settings.getTurnoverBufferMinutes(),
                settings.getCheckInMinutes(), settings.getCheckoutMinutes(), settings.getTransferMinutes());
    }

    @Transactional
    public BookingSettingsDto configureBookingSettings(BookingSettingsUpdate request) {
        queries.lockStoreConfiguration();
        ZoneId zone = zone(request.timezone());
        String currentTimezone = store.settings().getTimezone();
        if (currentTimezone != null && !currentTimezone.equals(zone.getId()) && hasFutureBookings()) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Cannot change timezone while future holds or confirmed bookings exist");
        }
        // A changed buffer or check-in / check-out / transfer time only affects bookings made from now on;
        // existing allocations keep the occupancy window they were created with.
        jdbc.update("""
                UPDATE store_settings
                SET timezone = ?, turnover_buffer_minutes = COALESCE(?, turnover_buffer_minutes),
                    check_in_minutes = COALESCE(?, check_in_minutes),
                    checkout_minutes = COALESCE(?, checkout_minutes),
                    transfer_minutes = COALESCE(?, transfer_minutes),
                    updated_at = NOW()
                """, zone.getId(), request.turnoverBufferMinutes(), request.checkInMinutes(),
                request.checkoutMinutes(), request.transferMinutes());
        // Read the row back: the StoreSettings entity loaded above still holds the old values.
        return jdbc.queryForObject("""
                SELECT timezone, turnover_buffer_minutes, check_in_minutes, checkout_minutes, transfer_minutes
                FROM store_settings
                """, (rs, row) -> new BookingSettingsDto(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                rs.getInt(5)));
    }

    @Transactional(readOnly = true)
    public List<ServiceOfferingDto> listOfferings() {
        // Requirements and "from" prices are read after the rows, not inside the RowMapper.
        return offeringRows().stream().map(row -> {
            List<String> requirements = queries.requirementsFor(row.code());
            if (!BookingResourceQueries.pricedByMachine(row.mode(), requirements)) {
                return row;
            }
            // V69: the machines' price profiles set how long each stage holds its machine (the longest cycle of
            // the commissioned machines of that type), exactly as customers book it.
            Integer primary = queries.longestCycle(requirements.get(0));
            Integer secondary = requirements.size() > 1 ? queries.longestCycle(requirements.get(1)) : null;
            return new ServiceOfferingDto(row.code(), row.name(), row.mode(), row.description(), row.enabled(),
                    queries.startingPrice(requirements), null,
                    primary != null ? primary : row.processingMinutes(),
                    secondary != null ? secondary : row.secondaryProcessingMinutes(),
                    row.checkInMinutes(), row.transferMinutes(),
                    row.checkoutMinutes(), row.configured(), false, true);
        }).toList();
    }

    private List<ServiceOfferingDto> offeringRows() {
        return jdbc.query("""
                SELECT d.code, d.name, d.service_mode, d.description,
                       d.processing_minutes, d.secondary_processing_minutes,
                       o.enabled, o.price, o.price_per_kg, o.processing_minutes_override,
                       o.secondary_processing_minutes_override,
                       d.service_mode = 'ASSISTED_LOCKER' by_weight,
                """ + BookingResourceQueries.STAGE_MINUTES_SQL + """

                FROM booking_service_definitions d
                CROSS JOIN store_settings s
                LEFT JOIN service_offerings o ON o.service_code = d.code
                WHERE d.active = TRUE
                ORDER BY CASE d.service_mode WHEN 'SELF_SERVICE' THEN 0 ELSE 1 END, d.name
                """, (rs, row) -> new ServiceOfferingDto(
                rs.getString("code"), rs.getString("name"), rs.getString("service_mode"),
                rs.getString("description"), rs.getObject("enabled") != null && rs.getBoolean("enabled"),
                rs.getBigDecimal("price"), rs.getBigDecimal("price_per_kg"),
                rs.getObject("processing_minutes_override") == null
                        ? rs.getInt("processing_minutes") : rs.getInt("processing_minutes_override"),
                rs.getObject("secondary_processing_minutes_override") == null
                        ? rs.getInt("secondary_processing_minutes")
                        : rs.getInt("secondary_processing_minutes_override"),
                rs.getInt("check_in_minutes"), rs.getInt("transfer_minutes"),
                rs.getInt("checkout_minutes"), rs.getObject("enabled") != null, rs.getBoolean("by_weight"), false));
    }

    @Transactional
    public ResourceDto updateResource(UUID resourceId, ResourceUpdate request) {
        queries.lockStoreConfiguration();
        if (!request.enabled() && hasFutureResourceAllocations(resourceId)) {
            throw BusinessException.of(ErrorCode.CONFLICT, "Cannot disable a resource with future holds or confirmed bookings");
        }
        int changed = jdbc.update("""
                UPDATE service_resources SET resource_code = ?, display_name = ?, enabled = ? WHERE id = ?
                """, request.resourceCode().trim(), request.displayName().trim(), request.enabled(), resourceId);
        if (changed == 0) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Resource not found");
        }
        return jdbc.queryForObject("""
                SELECT id, resource_type, resource_code, display_name, enabled FROM service_resources WHERE id = ?
                """, (rs, row) -> new ResourceDto(rs.getObject("id", UUID.class),
                rs.getString("resource_type"), rs.getString("resource_code"),
                rs.getString("display_name"), rs.getBoolean("enabled")), resourceId);
    }

    private static ZoneId zone(String timezone) {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException e) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Invalid IANA timezone");
        }
    }

    private boolean hasFutureBookings() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_bookings WHERE start_time > NOW() AND " + LIVE_BOOKING, Integer.class);
        return count != null && count > 0;
    }

    private boolean hasFutureReservations(String serviceCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM scheduled_bookings WHERE service_code = ? AND start_time > NOW() AND
                """ + LIVE_BOOKING, Integer.class, serviceCode);
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

    private void validateScheduleImpact(BookingHoursRequest request) {
        String timezone = store.settings().getTimezone();
        if (timezone == null) return;
        ZoneId zone = ZoneId.of(timezone);
        Map<DayOfWeek, DayHours> schedule = request.days().stream()
                .collect(java.util.stream.Collectors.toMap(DayHours::dayOfWeek, day -> day));
        List<LocalDateTime[]> reservations = jdbc.query("""
                SELECT start_time, expected_end_time FROM scheduled_bookings WHERE start_time > NOW() AND
                """ + LIVE_BOOKING, (rs, row) -> new LocalDateTime[] {
                rs.getTimestamp("start_time").toInstant().atZone(zone).toLocalDateTime(),
                rs.getTimestamp("expected_end_time").toInstant().atZone(zone).toLocalDateTime()
        });
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
