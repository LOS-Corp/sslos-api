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

    /** A branch open around the clock (00:00–23:59 means until midnight) offering WASH_ONLY at {@code price} VND. */
    public static UUID bookableBranch(JdbcTemplate jdbc, long price) {
        return bookableBranch(jdbc, price, LocalTime.MIDNIGHT, LocalTime.of(23, 59));
    }

    public static UUID bookableBranch(JdbcTemplate jdbc, long price, LocalTime opens, LocalTime closes) {
        UUID branchId = UUID.randomUUID();
        jdbc.update("INSERT INTO branches (id, name, status, created_at, updated_at) VALUES (?, ?, 'ACTIVE', NOW(), NOW())",
                branchId, "Branch " + branchId);
        jdbc.update("INSERT INTO branch_booking_settings (branch_id, timezone) VALUES (?, ?)", branchId, ZONE.getId());
        for (int day = 1; day <= 7; day++) {
            jdbc.update("INSERT INTO branch_booking_hours (branch_id, day_of_week, opens_at, closes_at) VALUES (?, ?, ?, ?)",
                    branchId, day, opens, closes);
        }
        jdbc.update("INSERT INTO branch_service_offerings (branch_id, service_code, enabled, price) VALUES (?, 'WASH_ONLY', TRUE, ?)",
                branchId, price);
        return branchId;
    }

    /** An online, commissioned, available washer in the branch. */
    public static UUID washer(JdbcTemplate jdbc, UUID branchId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO machines (id, machine_code, branch_id, type, status, deployment_status, online, created_at, updated_at)
                VALUES (?, ?, ?, 'WASHER', 'AVAILABLE', 'ACTIVE', TRUE, NOW(), NOW())
                """, id, "W" + id.toString().substring(0, 8).toUpperCase(), branchId);
        return id;
    }

    /**
     * An online LOCKER cabinet with one compartment, and ASSISTED_LOCKER offered at {@code price} VND.
     * Returns the compartment resource (what bookings allocate) and the cabinet machine.
     */
    public static Locker lockerCompartment(JdbcTemplate jdbc, UUID branchId, long price) {
        UUID cabinet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO machines (id, machine_code, branch_id, type, status, deployment_status, online, created_at, updated_at)
                VALUES (?, ?, ?, 'LOCKER', 'AVAILABLE', 'ACTIVE', TRUE, NOW(), NOW())
                """, cabinet, "L" + cabinet.toString().substring(0, 8).toUpperCase(), branchId);
        UUID compartment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO branch_service_resources (id, branch_id, resource_type, resource_code, display_name,
                                                      locker_machine_id, compartment_no)
                VALUES (?, ?, 'LOCKER', ?, 'Ô 1', ?, 1)
                """, compartment, branchId, "LK-" + cabinet.toString().substring(0, 6), cabinet);
        jdbc.update("INSERT INTO branch_service_offerings (branch_id, service_code, enabled, price) VALUES (?, 'ASSISTED_LOCKER', TRUE, ?)",
                branchId, price);
        return new Locker(compartment, cabinet);
    }

    public record Locker(UUID compartmentId, UUID cabinetId) {
    }
}
