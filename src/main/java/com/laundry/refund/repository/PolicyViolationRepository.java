package com.laundry.refund.repository;

import com.laundry.refund.domain.RefundPolicy.ViolationType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Rolling violation history of a customer (BR-12, FR-REF-03): no bulk reset, events age out after 90 days. */
@Repository
@RequiredArgsConstructor
public class PolicyViolationRepository {

    private final JdbcTemplate jdbc;

    /** Events in [{@code from}, {@code until}) — the rank of a new event is this count + 1. */
    public int countInWindow(UUID customerId, Instant from, Instant until) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM policy_violations
                WHERE customer_id = ? AND occurred_at >= ? AND occurred_at < ?
                """, Integer.class, customerId, Timestamp.from(from), Timestamp.from(until));
        return count == null ? 0 : count;
    }

    /** One order produces at most one event (unique booking_id). */
    public void record(UUID customerId, UUID bookingId, ViolationType type, Instant at) {
        jdbc.update("""
                INSERT INTO policy_violations (customer_id, booking_id, type, occurred_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (booking_id) DO NOTHING
                """, customerId, bookingId, type.name(), Timestamp.from(at));
    }
}
