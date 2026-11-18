package com.laundry.booking.service;

import com.laundry.booking.domain.SlotPlanner.AllocationPlan;
import com.laundry.booking.domain.SlotPlanner.Resource;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Laundry heavier than the washer's capacity (V61): the customer may move their unstarted washer slot to a
 * larger washer that is free right now. The slot keeps its length and starts now on the new machine; the
 * customer then unlocks and weighs again there (and pays any extra kilograms there).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoadMoveService {

    private final JdbcTemplate jdbc;
    private final MachineRepository machineRepository;
    private final BookingResourceQueries queries;

    /** A larger washer the customer can move to now. */
    public record LargerWasher(UUID machineId, String machineCode, String displayName, String location,
                               BigDecimal capacityKg) {
    }

    private record Slot(UUID bookingId, Instant from, Instant until, String cycleState, String bookingStatus) {
    }

    /** Free washers that hold {@code loadKg}, smallest first. */
    @Transactional(readOnly = true)
    public List<LargerWasher> largerWashers(UUID allocationId, UUID currentMachineId, BigDecimal loadKg) {
        Slot slot = slot(allocationId);
        AllocationPlan plan = planFromNow(slot);
        return machineRepository.findAll().stream()
                .filter(machine -> fits(machine, currentMachineId, loadKg))
                .filter(machine -> !busy(machine, plan))
                .sorted(Comparator.comparing(Machine::getCapacityKg).thenComparing(Machine::getMachineCode))
                .map(machine -> new LargerWasher(machine.getId(), machine.getMachineCode(),
                        machine.getDisplayName() == null || machine.getDisplayName().isBlank()
                                ? machine.getMachineCode() : machine.getDisplayName(),
                        machine.getLocationDescription(), machine.getCapacityKg()))
                .toList();
    }

    /**
     * Moves the washer slot to {@code targetMachineId}, starting now. Runs under the target machine's row lock
     * and re-checks that it is still free, so two customers cannot take the same washer.
     *
     * @return the code of the new machine
     */
    @Transactional
    public String move(UUID allocationId, UUID currentMachineId, BigDecimal loadKg, UUID targetMachineId) {
        Slot slot = slot(allocationId);
        if (!"PENDING".equals(slot.cycleState())) {
            throw BusinessException.of(ErrorCode.CONFLICT, "This wash has already started");
        }
        Machine target = machineRepository.findByIdForReservation(targetMachineId)
                .orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Machine not found"));
        AllocationPlan plan = planFromNow(slot);
        if (!fits(target, currentMachineId, loadKg)) {
            throw BusinessException.of(ErrorCode.MACHINE_TOO_SMALL, "This machine cannot take the laundry");
        }
        if (busy(target, plan)) {
            throw BusinessException.of(ErrorCode.SLOT_TAKEN, "This machine was just taken. Choose another one.");
        }
        jdbc.update("""
                UPDATE booking_resource_allocations
                SET resource_id = ?, occupied_from = ?, occupied_until = ?
                WHERE id = ? AND cycle_state = 'PENDING'
                """, target.getId(), OffsetDateTime.ofInstant(plan.from(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(plan.until(), ZoneOffset.UTC), allocationId);
        // The booking now starts with the moved slot, so its check-in / no-show window counts from now (BR-07).
        long shiftSeconds = Duration.between(slot.from(), plan.from()).getSeconds();
        if (shiftSeconds > 0) {
            jdbc.update("""
                    UPDATE scheduled_bookings
                    SET start_time = start_time + make_interval(secs => ?),
                        expected_end_time = expected_end_time + make_interval(secs => ?), updated_at = NOW()
                    WHERE id = ?
                    """, shiftSeconds, shiftSeconds, slot.bookingId());
        }
        log.info("Booking {} moved its washer slot {} to {} for {} kg", slot.bookingId(), allocationId,
                target.getMachineCode(), loadKg);
        return target.getMachineCode();
    }

    private boolean fits(Machine machine, UUID currentMachineId, BigDecimal loadKg) {
        return machine.getType() == MachineType.WASHER
                && !machine.getId().equals(currentMachineId)
                && machine.isCommissioned() && machine.isOnline()
                && machine.getStatus() == MachineStatus.AVAILABLE
                && machine.getCapacityKg() != null && machine.getCapacityKg().compareTo(loadKg) >= 0;
    }

    /** Another booking holds it in the window, or a customer is unlocking it right now. */
    private boolean busy(Machine machine, AllocationPlan plan) {
        Resource resource = new Resource("WASHER", machine.getId(), machine.getMachineCode(), null, null,
                machine.getStatus().name());
        if (!queries.busyResources(List.of(resource), plan, 0).isEmpty()) {
            return true;
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM machine_start_authorizations
                               WHERE machine_id = ? AND status IN ('PENDING', 'GRANTED', 'START_PENDING'))
                """, Boolean.class, machine.getId()));
    }

    /** Same length as the booked slot, starting at the current minute. */
    private static AllocationPlan planFromNow(Slot slot) {
        Instant now = Instant.ofEpochSecond(Instant.now().getEpochSecond() / 60 * 60);
        Duration length = Duration.between(slot.from(), slot.until());
        return new AllocationPlan("WASHER", "WASHING", now, now.plus(length));
    }

    private Slot slot(UUID allocationId) {
        List<Slot> slots = jdbc.query("""
                SELECT a.booking_id, a.occupied_from, a.occupied_until, a.cycle_state, b.status
                FROM booking_resource_allocations a JOIN scheduled_bookings b ON b.id = a.booking_id
                WHERE a.id = ? AND a.resource_type = 'WASHER'
                """, (rs, row) -> new Slot(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5)), allocationId);
        if (slots.isEmpty()) {
            throw BusinessException.of(ErrorCode.NOT_FOUND, "Washer slot not found");
        }
        return slots.getFirst();
    }
}
