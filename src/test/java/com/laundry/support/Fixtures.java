package com.laundry.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

/** Small SQL fixtures for integration tests that need real rows behind foreign keys. */
public final class Fixtures {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private Fixtures() {
    }

    /** A CUSTOMER account; returns its id (= JWT subject). */
    public static UUID customer(JdbcTemplate jdbc) {
        return user(jdbc, "CUSTOMER");
    }

    public static UUID user(JdbcTemplate jdbc, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, full_name, email, phone, password_hash, status) VALUES (?, ?, ?, ?, 'x', 'ACTIVE')",
                id, role + " test", id + "@test.vn", "+849" + String.format("%08d", (long) (Math.random() * 1e8)));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = ?", id, role);
        return id;
    }

    /** Makes the store open around the clock (00:00–23:59 means until midnight), offering WASH_ONLY at {@code price} VND. */
    public static void bookableStore(JdbcTemplate jdbc, long price) {
        bookableStore(jdbc, price, LocalTime.MIDNIGHT, LocalTime.of(23, 59));
    }

    /**
     * The one store (V58): sets its timezone and opening hours and offers WASH_ONLY at {@code price} VND. Tests
     * share the store, so every call overwrites what the previous test configured.
     */
    public static void bookableStore(JdbcTemplate jdbc, long price, LocalTime opens, LocalTime closes) {
        jdbc.update("""
                UPDATE store_settings SET timezone = ?, turnover_buffer_minutes = 0,
                    check_in_minutes = 5, checkout_minutes = 5, transfer_minutes = 10
                """, ZONE.getId());
        jdbc.update("DELETE FROM store_hours");
        for (int day = 1; day <= 7; day++) {
            jdbc.update("INSERT INTO store_hours (day_of_week, opens_at, closes_at) VALUES (?, ?, ?)", day, opens, closes);
        }
        offering(jdbc, "WASH_ONLY", price);
    }

    /**
     * An online, commissioned, available washer in the store. Its heartbeat is set a day ahead: these machines
     * are simulated, and the offline detector would otherwise mark them OFFLINE 30 s into a test.
     */
    public static UUID washer(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO machines (id, machine_code, type, status, deployment_status, online,
                                      last_heartbeat_at, created_at, updated_at, price_profile_id)
                VALUES (?, ?, 'WASHER', 'AVAILABLE', 'ACTIVE', TRUE, NOW() + interval '1 day', NOW(), NOW(), ?)
                """, id, "W" + id.toString().substring(0, 8).toUpperCase(),
                priceProfile(jdbc, "WASHER", offeredPrice(jdbc, "WASH_ONLY", 30_000)));
        return id;
    }

    /** An online, commissioned, available dryer in the store, simulated like {@link #washer}. */
    public static UUID dryer(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO machines (id, machine_code, type, status, deployment_status, online,
                                      last_heartbeat_at, created_at, updated_at, price_profile_id)
                VALUES (?, ?, 'DRYER', 'AVAILABLE', 'ACTIVE', TRUE, NOW() + interval '1 day', NOW(), NOW(), ?)
                """, id, "D" + id.toString().substring(0, 8).toUpperCase(),
                priceProfile(jdbc, "DRYER", offeredPrice(jdbc, "DRY_ONLY", 20_000)));
        return id;
    }

    /**
     * V68: washers and dryers are priced by their price profile only. The fixtures price each new machine like the
     * store's single-machine service ({@code WASH_ONLY} / {@code DRY_ONLY}) at the time it is created, so a booking of
     * one machine still costs what the test set with {@link #bookableStore} or {@link #offering}.
     */
    public static UUID priceProfile(JdbcTemplate jdbc, String machineType, long price) {
        String name = "Test " + machineType + " " + price;
        return jdbc.query("SELECT id FROM price_profiles WHERE lower(name) = lower(?)",
                        (rs, row) -> rs.getObject(1, UUID.class), name).stream().findFirst()
                .orElseGet(() -> jdbc.queryForObject("""
                        INSERT INTO price_profiles (name, machine_type, base_price, cycle_minutes)
                        VALUES (?, ?, ?, ?) RETURNING id
                        """, UUID.class, name, machineType, price, "DRYER".equals(machineType) ? 45 : 35));
    }

    private static long offeredPrice(JdbcTemplate jdbc, String serviceCode, long fallback) {
        return jdbc.query("SELECT price FROM service_offerings WHERE service_code = ? AND price > 0",
                        (rs, row) -> rs.getLong(1), serviceCode).stream().findFirst().orElse(fallback);
    }

    /**
     * The store sells {@code serviceCode} at {@code price} VND (replacing an earlier price). V65: self-service is
     * priced per cycle ({@code price}); assisted laundry costs {@code price} when booking plus {@code price} per kg
     * once staff weighed it.
     */
    public static void offering(JdbcTemplate jdbc, String serviceCode, long price) {
        jdbc.update("""
                INSERT INTO service_offerings (service_code, enabled, price, price_per_kg)
                SELECT ?, TRUE, ?, CASE WHEN assisted THEN ? END
                FROM (SELECT EXISTS (SELECT 1 FROM booking_service_definitions
                                     WHERE code = ? AND service_mode = 'ASSISTED_LOCKER') assisted) mode
                ON CONFLICT (service_code) DO UPDATE SET enabled = TRUE, price = EXCLUDED.price,
                    price_per_kg = EXCLUDED.price_per_kg,
                    processing_minutes_override = NULL, secondary_processing_minutes_override = NULL
                """, serviceCode, price, price, serviceCode);
    }

    /** A verified online payment of a transaction; returns its id. */
    public static UUID paidPayment(JdbcTemplate jdbc, UUID transactionId, long amount) {
        return payment(jdbc, transactionId, amount, "ONLINE");
    }

    public static UUID payment(JdbcTemplate jdbc, UUID transactionId, long amount, String method) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payments (id, transaction_id, amount, payment_method, status, paid_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PAID', NOW(), NOW(), NOW())
                """, id, transactionId, amount, method);
        return id;
    }

    /**
     * An online LOCKER cabinet with one compartment, and ASSISTED_LOCKER offered at {@code price} VND.
     * Returns the compartment resource (what bookings allocate) and the cabinet machine.
     */
    public static Locker lockerCompartment(JdbcTemplate jdbc, long price) {
        UUID cabinet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO machines (id, machine_code, type, status, deployment_status, online,
                                      last_heartbeat_at, created_at, updated_at)
                VALUES (?, ?, 'LOCKER', 'AVAILABLE', 'ACTIVE', TRUE, NOW() + interval '1 day', NOW(), NOW())
                """, cabinet, "L" + cabinet.toString().substring(0, 8).toUpperCase());
        UUID compartment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO service_resources (id, resource_type, resource_code, display_name,
                                               locker_machine_id, compartment_no)
                VALUES (?, 'LOCKER', ?, 'Ô 1', ?, 1)
                """, compartment, "LK-" + cabinet.toString().substring(0, 6), cabinet);
        offering(jdbc, "ASSISTED_LOCKER", price);
        return new Locker(compartment, cabinet);
    }

    public record Locker(UUID compartmentId, UUID cabinetId) {
    }
}
