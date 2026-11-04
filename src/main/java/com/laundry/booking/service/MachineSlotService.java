package com.laundry.booking.service;

import lombok.RequiredArgsConstructor;
import com.laundry.config.BookingProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer's machine slots, read straight from {@code booking_resource_allocations} (ADR-002): the
 * allocation is both the hold on the machine and the record of its cycle (PENDING → RUNNING → DONE).
 * QR check-in asks which slot a customer may start; the machine reports when a slot's cycle starts.
 */
@Service
@RequiredArgsConstructor
public class MachineSlotService {

    /** Bookings whose machines can still be started: paid, and not finished or released. */
    private static final String STARTABLE_BOOKINGS = "('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING')";

    private final JdbcTemplate jdbc;
    private final BookingProperties props;

    /**
     * One washer or dryer allocation of a booking. {@code used} once its cycle has started: a booking
     * pays for exactly one cycle per machine.
     */
    public record MachineSlot(UUID allocationId, UUID bookingId, UUID transactionId, Instant from, Instant until,
                              String cycleState) {

        public boolean used() {
            return !"PENDING".equals(cycleState);
        }
    }

    /** The customer's live slots on this machine, earliest first. */
    @Transactional(readOnly = true)
    public List<MachineSlot> customerSlots(UUID machineId, UUID customerId) {
        return jdbc.query("""
                SELECT a.id, a.booking_id, b.transaction_id, a.occupied_from, a.occupied_until, a.cycle_state
                FROM booking_resource_allocations a
                JOIN scheduled_bookings b ON b.id = a.booking_id
                JOIN transactions t ON t.id = b.transaction_id
                WHERE a.resource_id = ? AND t.customer_id = ? AND a.cycle_state IS NOT NULL
                  AND b.status IN """ + STARTABLE_BOOKINGS + """

                ORDER BY a.occupied_from
                """, (rs, row) -> new MachineSlot(rs.getObject("id", UUID.class), rs.getObject("booking_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), rs.getTimestamp("occupied_from").toInstant(),
                rs.getTimestamp("occupied_until").toInstant(), rs.getString("cycle_state")), machineId, customerId);
    }

    /**
     * End of the hold of another customer's earlier turn on this machine that was actually used (its cycle
     * started) and is not over yet. Until then their laundry may still be in the drum: the washer has no
     * door switch, so the booked hold (cycle + checkout + turnover buffer) stands in for "unloaded".
     */
    @Transactional(readOnly = true)
    public Optional<Instant> previousTurnHeldUntil(UUID machineId, UUID customerId, Instant slotFrom, Instant now) {
        Timestamp until = jdbc.queryForObject("""
                SELECT MAX(a.occupied_until)
                FROM booking_resource_allocations a
                JOIN scheduled_bookings b ON b.id = a.booking_id
                JOIN transactions t ON t.id = b.transaction_id
                WHERE a.resource_id = ? AND t.customer_id <> ?
                  AND a.cycle_state IN ('RUNNING', 'DONE')
                  AND a.occupied_from < ? AND a.occupied_until > ?
                """, Timestamp.class, machineId, customerId, Timestamp.from(slotFrom), Timestamp.from(now));
        return Optional.ofNullable(until).map(Timestamp::toInstant);
    }

    /** Another customer has an unstarted turn before {@code slotFrom} whose check-in has not closed yet. */
    @Transactional(readOnly = true)
    public boolean earlierTurnStillOpen(UUID machineId, UUID customerId, Instant slotFrom, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM booking_resource_allocations a
                    JOIN scheduled_bookings b ON b.id = a.booking_id
                    JOIN transactions t ON t.id = b.transaction_id
                    WHERE a.resource_id = ? AND t.customer_id <> ? AND a.active AND a.cycle_state = 'PENDING'
                      AND a.occupied_from < ? AND a.occupied_from > ?)
                """, Boolean.class, machineId, customerId, Timestamp.from(slotFrom),
                Timestamp.from(now.minus(props.checkInClosesAfter()))));
    }

    /** The machine accepted START for this slot. False when it was already running or finished. */
    @Transactional
    public boolean markRunning(UUID allocationId) {
        return jdbc.update("""
                UPDATE booking_resource_allocations SET cycle_state = 'RUNNING'
                WHERE id = ? AND cycle_state = 'PENDING'
                """, allocationId) > 0;
    }
}
