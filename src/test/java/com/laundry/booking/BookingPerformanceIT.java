package com.laundry.booking;

import com.laundry.booking.dto.BookingDtos.MachineSearchResponse;
import com.laundry.booking.dto.BookingDtos.RecommendationsResponse;
import com.laundry.booking.dto.BookingDtos.TimelineAvailabilityResponse;
import com.laundry.booking.service.BookingAvailabilityService;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import com.laundry.support.SqlCounter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance of the booking read paths on PostgreSQL. The branch has:
 * <ul>
 *   <li>20 washers and 20 dryers;</li>
 *   <li>960 confirmed bookings over the 48-hour horizon;</li>
 *   <li>50,000 finished bookings in its history.</li>
 * </ul>
 * For each call the test records the SQL statements issued and the median wall time, and writes
 * {@code target/booking-performance.md}. It also holds the statement budgets, so an N+1 cannot come back
 * unnoticed. Every measured call must return real results: an empty answer would make the numbers
 * meaningless.
 */
@IntegrationTest
@Import(SqlCounter.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookingPerformanceIT {

    private static final int MACHINES_PER_TYPE = 20;
    private static final int HISTORY_PER_MACHINE = 1250;
    private static final int WARMUP = 3;
    private static final int RUNS = 31;

    @Autowired private BookingAvailabilityService availability;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate tx;

    private UUID branchId;
    private final Map<String, long[]> results = new LinkedHashMap<>();
    private final StringBuilder trace = new StringBuilder();

    @BeforeAll
    void seed() {
        branchId = Fixtures.bookableBranch(jdbc, 40_000);
        jdbc.update("INSERT INTO branch_service_offerings (branch_id, service_code, enabled, price) VALUES (?, 'WASH_DRY', TRUE, 70000)",
                branchId);
        List<UUID> machines = new ArrayList<>();
        for (int i = 0; i < MACHINES_PER_TYPE; i++) {
            machines.add(Fixtures.washer(jdbc, branchId));
            UUID dryer = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO machines (id, machine_code, branch_id, type, status, deployment_status, online, created_at, updated_at)
                    VALUES (?, ?, ?, 'DRYER', 'AVAILABLE', 'ACTIVE', TRUE, NOW(), NOW())
                    """, dryer, "D" + dryer.toString().substring(0, 8).toUpperCase(), branchId);
            machines.add(dryer);
        }
        keepMachinesOnline();
        UUID customer = Fixtures.customer(jdbc);
        seedHorizon(machines, customer);
        tx.executeWithoutResult(status -> seedHistory(machines, customer));
        jdbc.execute("ANALYZE booking_resource_allocations");
        jdbc.execute("ANALYZE scheduled_bookings");
    }

    @Test
    @DisplayName("Booking read paths: SQL statements per call and median latency on a busy branch")
    void readPaths() {
        LocalDate tomorrow = LocalDate.now(Fixtures.ZONE).plusDays(1);
        Instant now = Instant.now();
        Instant desired = now.truncatedTo(ChronoUnit.MINUTES).plus(3, ChronoUnit.HOURS).plus(13, ChronoUnit.MINUTES);
        Predicate<TimelineAvailabilityResponse> timelineHasMachines = response -> response.resources().size() >= MACHINES_PER_TYPE;
        Predicate<MachineSearchResponse> searchFoundMachines = response -> response.options().size() == MACHINES_PER_TYPE;
        Predicate<RecommendationsResponse> recommended = response -> !response.recommendations().isEmpty();

        measure("timeline WASH_ONLY (1 day)", 8,
                () -> availability.timelineAvailability(branchId, "WASH_ONLY", tomorrow, null, null), timelineHasMachines);
        measure("timeline WASH_DRY (1 day)", 10,
                () -> availability.timelineAvailability(branchId, "WASH_DRY", tomorrow, null, null), timelineHasMachines);
        measure("recommendations WASH_DRY", 10,
                () -> availability.recommendations(branchId, "WASH_DRY", desired, null, null, null), recommended);
        measure("machine search WASH_ONLY (24 h)", 8, () -> availability.machineSearch(branchId, "WASH_ONLY", now,
                now.plus(24, ChronoUnit.HOURS), null, null, null), searchFoundMachines);
        measure("machine search WASH_DRY (24 h)", 10, () -> availability.machineSearch(branchId, "WASH_DRY", now,
                now.plus(24, ChronoUnit.HOURS), null, null, null), searchFoundMachines);
    }

    /**
     * Warms up, counts the statements of one call (must stay within {@code budget}), then times {@link #RUNS}
     * calls. Each call must satisfy {@code realResult}, so a fixture that broke cannot pass as "fast".
     */
    private <T> void measure(String name, int budget, Supplier<T> call, Predicate<T> realResult) {
        keepMachinesOnline();
        for (int i = 0; i < WARMUP; i++) {
            assertThat(call.get()).as(name + " returns real results").matches(realResult);
        }
        SqlCounter.reset();
        assertThat(call.get()).matches(realResult);
        long statements = SqlCounter.count();
        trace.append("## ").append(name).append(System.lineSeparator());
        SqlCounter.statements().forEach(sql -> trace.append("- ").append(sql.lines().findFirst().orElse(""))
                .append(System.lineSeparator()));
        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long started = System.nanoTime();
            T result = call.get();
            times[i] = (System.nanoTime() - started) / 1_000;
            assertThat(result).matches(realResult);
        }
        Arrays.sort(times);
        results.put(name, new long[]{statements, times[RUNS / 2], times[RUNS * 9 / 10]});
        if (!Boolean.getBoolean("perf.baseline")) { // -Dperf.baseline=true measures old code without budgets
            assertThat(statements).as(name + " SQL statements").isLessThanOrEqualTo(budget);
        }
    }

    /** The offline detector marks machines without a recent heartbeat OFFLINE; these are simulated, so keep them alive. */
    private void keepMachinesOnline() {
        jdbc.update("UPDATE machines SET online = TRUE, status = 'AVAILABLE', last_heartbeat_at = NOW() + interval '1 day' WHERE branch_id = ?",
                branchId);
    }

    /** Every machine is booked for 45 minutes every 2 hours across the horizon, staggered per machine. */
    private void seedHorizon(List<UUID> machines, UUID customer) {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(30, ChronoUnit.MINUTES);
        List<Object[]> transactions = new ArrayList<>();
        List<Object[]> bookings = new ArrayList<>();
        List<Object[]> allocations = new ArrayList<>();
        for (int m = 0; m < machines.size(); m++) {
            boolean washer = m % 2 == 0;
            for (int slot = 0; slot < 24; slot++) {
                Instant start = base.plus(Duration.ofHours(2L * slot)).plus(Duration.ofMinutes(7L * (m / 2)));
                Instant end = start.plus(45, ChronoUnit.MINUTES);
                UUID transaction = UUID.randomUUID();
                UUID booking = UUID.randomUUID();
                transactions.add(new Object[]{transaction, customer});
                bookings.add(new Object[]{booking, transaction, branchId, Timestamp.from(start), Timestamp.from(end), Timestamp.from(start)});
                allocations.add(new Object[]{booking, machines.get(m), washer ? "WASHER" : "DRYER", washer ? "WASHING" : "DRYING",
                        Timestamp.from(start), Timestamp.from(end)});
            }
        }
        jdbc.batchUpdate("""
                INSERT INTO transactions (id, customer_id, service_type, status, total_amount, created_at, updated_at)
                VALUES (?, ?, 'SELF_SERVICE', 'PAID', 40000, NOW(), NOW())
                """, transactions);
        jdbc.batchUpdate("""
                INSERT INTO scheduled_bookings (id, transaction_id, branch_id, service_code, status, start_time,
                                                expected_end_time, hold_expires_at, timeline)
                VALUES (?, ?, ?, 'WASH_ONLY', 'CONFIRMED', ?, ?, ?, '[]'::jsonb)
                """, bookings);
        jdbc.batchUpdate("""
                INSERT INTO booking_resource_allocations
                  (booking_id, resource_id, resource_type, phase, occupied_from, occupied_until, cycle_state)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                """, allocations);
    }

    /**
     * Months of finished bookings on the same machines, every 3 h going back. They no longer hold anything,
     * but a query that cannot use an index still has to read them.
     */
    private void seedHistory(List<UUID> machines, UUID customer) {
        jdbc.update("""
                CREATE TEMP TABLE history ON COMMIT DROP AS
                SELECT gen_random_uuid() AS tx, gen_random_uuid() AS booking, m.id AS machine, m.type,
                       NOW() - make_interval(hours => 3 * g) AS start_at
                FROM unnest(?::uuid[]) AS u(id) JOIN machines m ON m.id = u.id, generate_series(1, ?) g
                """, machines.toArray(UUID[]::new), HISTORY_PER_MACHINE);
        jdbc.update("""
                INSERT INTO transactions (id, customer_id, service_type, status, total_amount, created_at, updated_at)
                SELECT tx, ?, 'SELF_SERVICE', 'COMPLETED', 40000, start_at, start_at FROM history
                """, customer);
        jdbc.update("""
                INSERT INTO scheduled_bookings (id, transaction_id, branch_id, service_code, status, start_time,
                                                expected_end_time, hold_expires_at, timeline)
                SELECT booking, tx, ?, 'WASH_ONLY', 'COMPLETED', start_at, start_at + interval '45 minutes', start_at,
                       '[]'::jsonb
                FROM history
                """, branchId);
        jdbc.update("""
                INSERT INTO booking_resource_allocations
                  (booking_id, resource_id, resource_type, phase, occupied_from, occupied_until, cycle_state, active)
                SELECT booking, machine, type, CASE type WHEN 'WASHER' THEN 'WASHING' ELSE 'DRYING' END,
                       start_at, start_at + interval '45 minutes', 'DONE', FALSE
                FROM history
                """);
    }

    @Test
    @DisplayName("The occupancy query uses the V44 index and never reads the finished history")
    void occupancyQueryUsesTheIndex() throws IOException {
        UUID[] machines = jdbc.queryForList("SELECT id FROM machines WHERE branch_id = ?", UUID.class, branchId)
                .toArray(UUID[]::new);
        List<String> plan = jdbc.queryForList("""
                EXPLAIN (ANALYZE, BUFFERS)
                SELECT a.resource_id, a.occupied_from, a.occupied_until
                FROM booking_resource_allocations a
                JOIN scheduled_bookings b ON b.id = a.booking_id
                WHERE a.active
                  AND a.resource_id = ANY (?)
                  AND tstzrange(a.occupied_from, a.occupied_until, '[)') && tstzrange(NOW(), NOW() + interval '1 day', '[)')
                  AND (b.status <> 'HELD' OR b.hold_expires_at > NOW())
                """, String.class, (Object) machines);
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target", "explain-occupancy.txt"), plan);
        assertThat(String.join(System.lineSeparator(), plan)).doesNotContain("Seq Scan on booking_resource_allocations");
    }

    @AfterAll
    void report() throws IOException {
        StringBuilder table = new StringBuilder("""
                | Call | SQL statements | Median (ms) | p90 (ms) |
                |------|---------------:|------------:|---------:|
                """);
        results.forEach((name, r) -> table.append("| %s | %d | %.1f | %.1f |%n".formatted(name, r[0], r[1] / 1000.0, r[2] / 1000.0)));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "booking-sql-trace.md"), trace);
        Files.writeString(Path.of("target", "booking-performance.md"),
                "Branch: %d washers, %d dryers, %d bookings in 48 h, %d finished bookings in history%n%n%s".formatted(
                        MACHINES_PER_TYPE, MACHINES_PER_TYPE, MACHINES_PER_TYPE * 2 * 24,
                        MACHINES_PER_TYPE * 2 * HISTORY_PER_MACHINE, table));
    }
}
