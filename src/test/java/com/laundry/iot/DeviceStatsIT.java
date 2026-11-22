package com.laundry.iot;

import com.laundry.iot.dto.ProvisioningDtos.DeviceStats;
import com.laundry.iot.service.DeviceStatsService;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V62: maintenance count, rentals, revenue and payback of a device on its machine. */
@IntegrationTest
@DisplayName("Device stats")
class DeviceStatsIT {

    @Autowired private DeviceStatsService stats;
    @Autowired private JdbcTemplate jdbc;

    private UUID washer;
    private UUID dryer;
    private UUID customer;
    private String deviceId;
    private int slot;

    @BeforeEach
    void setUp() {
        Fixtures.bookableStore(jdbc, 10_000);
        washer = Fixtures.washer(jdbc);
        dryer = Fixtures.dryer(jdbc);
        customer = Fixtures.customer(jdbc);
        deviceId = "ESP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbc.update("""
                INSERT INTO iot_devices (id, device_id, status, device_type, machine_id, enrolled_at, verified_at,
                                         activated_at, updated_at, manufacturer, model, purchase_cost)
                VALUES (?, ?, 'ACTIVE', 'WASHER', ?, NOW() - interval '10 days', NOW() - interval '10 days',
                        NOW() - interval '5 days', NOW(), 'Aimi', 'GA25', 1000000)
                """, UUID.randomUUID(), deviceId, washer);
        jdbc.update("UPDATE machines SET device_id = ? WHERE id = ?", deviceId, washer);
    }

    @Test
    @DisplayName("Counts real cycles since attachment, splits shared bookings, adds top-ups, subtracts refunds")
    void statsOfAnAttachedDevice() {
        // 1) washer only, paid 100 000, +2 kg top-up at 10 000/kg, 30 000 refunded → 90 000
        UUID first = booking(100_000, washer);
        topUp(first, 2, 10_000);
        refund(first, 30_000);
        consumed(first, false, OffsetDateTime.now().minusDays(1));
        // 2) washer + dryer, paid 200 000 → 100 000 for the washer
        UUID second = booking(200_000, washer, dryer);
        consumed(second, false, OffsetDateTime.now().minusHours(3));
        // not counted: an admin test run, and a cycle from before this device was attached
        consumed(booking(500_000, washer), true, OffsetDateTime.now().minusHours(2));
        consumed(booking(700_000, washer), false, OffsetDateTime.now().minusDays(7));
        // maintenance: one now, one before attachment
        event("MAINTENANCE_STARTED", OffsetDateTime.now().minusDays(2));
        event("MAINTENANCE_STARTED", OffsetDateTime.now().minusDays(8));
        event("MAINTENANCE_COMPLETED", OffsetDateTime.now().minusDays(2));

        DeviceStats result = stats.stats(deviceId);

        assertThat(result.rentalCount()).isEqualTo(2);
        assertThat(result.maintenanceCount()).isEqualTo(1);
        assertThat(result.revenue()).isEqualByComparingTo("190000");
        assertThat(result.purchaseCost()).isEqualByComparingTo("1000000");
        assertThat(result.paybackPercent()).isEqualByComparingTo("19.0");
        assertThat(result.remaining()).isEqualByComparingTo("810000");
        assertThat(result.machineCode()).startsWith("W");
    }

    @Test
    @DisplayName("A device without a machine has no usage, and no payback without a purchase cost")
    void unattachedDevice() {
        jdbc.update("UPDATE iot_devices SET machine_id = NULL, purchase_cost = NULL WHERE device_id = ?", deviceId);
        DeviceStats result = stats.stats(deviceId);
        assertThat(result.machineCode()).isNull();
        assertThat(result.rentalCount()).isZero();
        assertThat(result.revenue()).isEqualByComparingTo("0");
        assertThat(result.paybackPercent()).isNull();
        assertThat(result.remaining()).isNull();
    }

    private UUID booking(long paid, UUID... machines) {
        UUID transactionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions (id, customer_id, service_type, status, total_amount, paid_at)
                VALUES (?, ?, 'SELF_SERVICE', 'PAID', ?, NOW())""", transactionId, customer, paid);
        UUID bookingId = UUID.randomUUID();
        // A machine's allocations may not overlap: every booking gets its own two-hour slot.
        OffsetDateTime start = OffsetDateTime.now().minusDays(20).plusHours(2L * slot++);
        jdbc.update("""
                INSERT INTO scheduled_bookings (id, transaction_id, service_code, status, start_time,
                                                expected_end_time, hold_expires_at, timeline)
                VALUES (?, ?, 'WASH_ONLY', 'CONFIRMED', ?, ?, ?, '[]'::jsonb)""",
                bookingId, transactionId, start, start.plusHours(1), start);
        for (UUID machine : machines) {
            jdbc.update("""
                    INSERT INTO booking_resource_allocations
                      (booking_id, resource_id, resource_type, phase, occupied_from, occupied_until, cycle_state)
                    VALUES (?, ?, ?, 'WASHING', ?, ?, 'PENDING')""",
                    bookingId, machine, machine.equals(dryer) ? "DRYER" : "WASHER", start, start.plusHours(1));
        }
        return bookingId;
    }

    private void consumed(UUID bookingId, boolean testRun, OffsetDateTime at) {
        jdbc.update("""
                INSERT INTO machine_start_authorizations (id, user_id, machine_id, machine_code, status, expires_at,
                                                          created_at, consumed_at, test_run, booking_id, allocation_id)
                SELECT ?, ?, m.id, m.machine_code, 'CONSUMED', ?, ?, ?, ?, ?, a.id
                FROM machines m JOIN booking_resource_allocations a ON a.resource_id = m.id AND a.booking_id = ?
                WHERE m.id = ?""",
                UUID.randomUUID(), customer, at.plusMinutes(2), at, at, testRun, bookingId, bookingId, washer);
    }

    private void topUp(UUID bookingId, int kg, long pricePerKg) {
        UUID transactionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions (id, customer_id, service_type, status, total_amount, paid_at)
                VALUES (?, ?, 'SELF_SERVICE', 'PAID', ?, NOW())""", transactionId, customer, kg * pricePerKg);
        jdbc.update("""
                INSERT INTO booking_load_topups (transaction_id, booking_id, extra_kg, price_per_kg, status, paid_at)
                VALUES (?, ?, ?, ?, 'PAID', NOW())""", transactionId, bookingId, kg, pricePerKg);
    }

    private void refund(UUID bookingId, long amount) {
        UUID transactionId = jdbc.queryForObject("SELECT transaction_id FROM scheduled_bookings WHERE id = ?",
                UUID.class, bookingId);
        UUID payment = Fixtures.paidPayment(jdbc, transactionId, 100_000);
        jdbc.update("""
                INSERT INTO refund_requests (payment_id, transaction_id, requested_by, amount, reason, status)
                VALUES (?, ?, ?, ?, 'test', 'COMPLETED')""", payment, transactionId, customer, amount);
    }

    private void event(String type, OffsetDateTime at) {
        jdbc.update("""
                INSERT INTO device_events (machine_id, machine_code, event_type, created_at)
                SELECT id, machine_code, ?, ? FROM machines WHERE id = ?""", type, at, washer);
    }
}
