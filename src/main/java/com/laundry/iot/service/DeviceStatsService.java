package com.laundry.iot.service;

import com.laundry.iot.dto.ProvisioningDtos.DeviceStats;
import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.repository.MachineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Use and payback of a device (V62) on its current machine, counted from when it was attached:
 * <ul>
 *   <li><b>maintenance:</b> {@code MAINTENANCE_STARTED} events of the machine;</li>
 *   <li><b>rentals:</b> bookings whose start authorization on the machine was consumed (a real cycle; admin test
 *       runs excluded);</li>
 *   <li><b>revenue:</b> for those bookings, the paid booking amount plus paid extra kilograms minus completed
 *       refunds, split evenly between the washers/dryers the booking used.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DeviceStatsService {

    private static final String RENTALS = """
            SELECT DISTINCT booking_id FROM machine_start_authorizations
            WHERE machine_id = ? AND status = 'CONSUMED' AND NOT test_run
              AND booking_id IS NOT NULL AND consumed_at >= ?""";

    private static final String REVENUE = """
            WITH rentals AS (%s),
            shares AS (
                SELECT r.booking_id,
                       GREATEST(1, (SELECT count(DISTINCT a.resource_id) FROM booking_resource_allocations a
                                    WHERE a.booking_id = r.booking_id AND a.resource_type IN ('WASHER', 'DRYER'))) AS machines,
                       COALESCE((SELECT t.total_amount FROM scheduled_bookings b
                                 JOIN transactions t ON t.id = b.transaction_id
                                 WHERE b.id = r.booking_id AND t.status = 'PAID'), 0)
                     + COALESCE((SELECT sum(u.extra_kg * u.price_per_kg) FROM booking_load_topups u
                                 WHERE u.booking_id = r.booking_id AND u.status = 'PAID'), 0)
                     + COALESCE((SELECT sum(e.price) FROM dry_extensions e
                                 WHERE e.booking_id = r.booking_id AND e.status IN ('PAID', 'APPLIED')), 0)
                     - COALESCE((SELECT sum(f.amount) FROM refund_requests f
                                 JOIN scheduled_bookings b ON b.transaction_id = f.transaction_id
                                 WHERE b.id = r.booking_id AND f.status = 'COMPLETED'), 0) AS net
                FROM rentals r)
            SELECT COALESCE(sum(net / machines), 0) FROM shares""".formatted(RENTALS);

    private final IotDeviceRepository deviceRepository;
    private final MachineRepository machineRepository;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public DeviceStats stats(String deviceId) {
        IotDevice device = deviceRepository.findByDeviceId(deviceId.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ProvisioningRejectedException(HttpStatus.NOT_FOUND, "Unknown device " + deviceId));
        Machine machine = device.getMachineId() == null ? null
                : machineRepository.findById(device.getMachineId()).orElse(null);
        BigDecimal cost = device.getPurchaseCost();
        if (machine == null) {
            return of(device, null, null, 0, 0, BigDecimal.ZERO, cost);
        }
        OffsetDateTime since = device.getActivatedAt() != null ? device.getActivatedAt()
                : device.getVerifiedAt() != null ? device.getVerifiedAt() : device.getEnrolledAt();
        UUID machineId = machine.getId();
        Long maintenance = jdbc.queryForObject("""
                SELECT count(*) FROM device_events
                WHERE machine_id = ? AND event_type = 'MAINTENANCE_STARTED' AND created_at >= ?""",
                Long.class, machineId, since);
        Long rentals = jdbc.queryForObject("SELECT count(*) FROM (" + RENTALS + ") r", Long.class, machineId, since);
        BigDecimal revenue = jdbc.queryForObject(REVENUE, BigDecimal.class, machineId, since);
        return of(device, machine.getMachineCode(), since, nz(maintenance), nz(rentals),
                revenue == null ? BigDecimal.ZERO : revenue.setScale(0, RoundingMode.HALF_UP), cost);
    }

    static DeviceStats of(IotDevice device, String machineCode, OffsetDateTime since, long maintenance, long rentals,
                          BigDecimal revenue, BigDecimal cost) {
        BigDecimal percent = null;
        BigDecimal remaining = null;
        if (cost != null && cost.signum() > 0) {
            percent = revenue.multiply(BigDecimal.valueOf(100)).divide(cost, 1, RoundingMode.HALF_UP);
            remaining = cost.subtract(revenue).max(BigDecimal.ZERO).setScale(0, RoundingMode.HALF_UP);
        }
        return new DeviceStats(device.getDeviceId(), machineCode, since, maintenance, rentals, revenue, cost,
                percent, remaining);
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
