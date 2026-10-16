package com.laundry.machine.service;

import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Validates and enforces machine state transitions.
 *
 * <p>The backend does NOT blindly trust status values from devices.
 * A device can only move to a status that is a valid transition from the current state.
 * This prevents rogue devices, stale messages, or bugs from putting machines into
 * invalid states.
 *
 * <p>State transition table:
 * <pre>
 * AVAILABLE      → RESERVED, RUNNING, ERROR, OFFLINE
 * RESERVED       → WAITING_START, RUNNING, AVAILABLE, ERROR, OFFLINE (booking cancelled/expired)
 * WAITING_START  → RUNNING, RESERVED, ERROR, OFFLINE
 * RUNNING        → COMPLETED, AVAILABLE, PAUSED, ERROR, OFFLINE
 * PAUSED         → RUNNING, ERROR, OFFLINE
 * COMPLETED      → AVAILABLE, OFFLINE
 * ERROR          → AVAILABLE, OUT_OF_SERVICE, OFFLINE
 * OFFLINE        → AVAILABLE (reconnect), ERROR
 * OUT_OF_SERVICE → AVAILABLE (staff action)
 * </pre>
 *
 * <p>AVAILABLE/RESERVED → RUNNING is allowed because the firmware only runs a
 * cycle after the START authorization handshake (QR check-in or admin test
 * run → AUTHORIZE_START → START_REQUEST → START_APPROVAL); the device is the
 * source of truth for the physical drum. RUNNING → AVAILABLE covers a lost
 * COMPLETED message (status is published with QoS 0).
 */
@Component
@Slf4j
public class MachineStateService {

    private static final Map<MachineStatus, Set<MachineStatus>> VALID_TRANSITIONS = Map.of(
        MachineStatus.AVAILABLE,      EnumSet.of(MachineStatus.RESERVED, MachineStatus.RUNNING, MachineStatus.ERROR,
                                                 MachineStatus.OFFLINE, MachineStatus.OUT_OF_SERVICE),
        MachineStatus.RESERVED,       EnumSet.of(MachineStatus.WAITING_START, MachineStatus.RUNNING, MachineStatus.AVAILABLE,
                                                 MachineStatus.ERROR, MachineStatus.OFFLINE),
        MachineStatus.WAITING_START,  EnumSet.of(MachineStatus.RUNNING, MachineStatus.RESERVED, MachineStatus.ERROR, MachineStatus.OFFLINE),
        MachineStatus.RUNNING,        EnumSet.of(MachineStatus.COMPLETED, MachineStatus.AVAILABLE, MachineStatus.PAUSED,
                                                 MachineStatus.ERROR, MachineStatus.OFFLINE),
        MachineStatus.PAUSED,         EnumSet.of(MachineStatus.RUNNING, MachineStatus.ERROR, MachineStatus.OFFLINE),
        MachineStatus.COMPLETED,      EnumSet.of(MachineStatus.AVAILABLE, MachineStatus.OFFLINE),
        MachineStatus.ERROR,          EnumSet.of(MachineStatus.AVAILABLE, MachineStatus.OUT_OF_SERVICE, MachineStatus.OFFLINE),
        MachineStatus.OFFLINE,        EnumSet.of(MachineStatus.AVAILABLE, MachineStatus.ERROR, MachineStatus.OUT_OF_SERVICE),
        MachineStatus.OUT_OF_SERVICE, EnumSet.of(MachineStatus.AVAILABLE, MachineStatus.OFFLINE)
    );

    /**
     * Check whether a transition from {@code current} to {@code next} is valid.
     *
     * @param current the machine's current status
     * @param next    the proposed next status
     * @return true if the transition is allowed
     */
    public boolean isValidTransition(MachineStatus current, MachineStatus next) {
        if (current == next) return true; // same-state "transition" is a no-op
        Set<MachineStatus> allowed = VALID_TRANSITIONS.get(current);
        return allowed != null && allowed.contains(next);
    }

    /**
     * Attempt to parse a device-reported status string into the {@link MachineStatus} enum.
     *
     * <p>Maps device-reported values (which may differ slightly) to backend enum values.
     * Unknown values are rejected (returns null) so they are not blindly accepted.
     *
     * @param rawStatus the status string from the device payload
     * @return the mapped MachineStatus, or null if unknown/invalid
     */
    public MachineStatus parseDeviceStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) return null;
        return switch (rawStatus.toUpperCase().trim()) {
            case "AVAILABLE"      -> MachineStatus.AVAILABLE;
            case "RUNNING"        -> MachineStatus.RUNNING;
            case "COMPLETED"      -> MachineStatus.COMPLETED;
            case "ERROR"          -> MachineStatus.ERROR;
            case "PAUSED"         -> MachineStatus.PAUSED;
            case "OFFLINE"        -> MachineStatus.OFFLINE;
            case "RESERVED"       -> MachineStatus.RESERVED;
            case "WAITING_START"  -> MachineStatus.WAITING_START;
            case "OUT_OF_SERVICE" -> MachineStatus.OUT_OF_SERVICE;
            // Legacy/alternate ESP32 values
            case "IDLE"           -> MachineStatus.AVAILABLE;
            // Firmware GA25 cycle phases: CHECKING → WASHING → SPINNING
            case "WASH", "WASHING", "CHECKING", "SPINNING", "SPIN" -> MachineStatus.RUNNING;
            case "DONE", "FINISHED" -> MachineStatus.COMPLETED;
            case "FAULT", "ERR"   -> MachineStatus.ERROR;
            default -> {
                log.warn("Unknown device status value: '{}' — rejecting", rawStatus);
                yield null;
            }
        };
    }

    /**
     * Apply the device-reported status to a machine, respecting transition rules.
     *
     * @param machine   the machine to update
     * @param rawStatus the raw status string from the device
     * @return true if the status was updated, false if rejected
     */
    public boolean applyDeviceStatus(Machine machine, String rawStatus) {
        MachineStatus incoming = parseDeviceStatus(rawStatus);
        if (incoming == null) {
            log.warn("[{}] Rejected unknown device status: '{}'", machine.getMachineCode(), rawStatus);
            return false;
        }

        MachineStatus current = machine.getStatus();
        if (current == incoming) {
            return false; // No change
        }

        if (!isValidTransition(current, incoming)) {
            log.warn("[{}] Invalid state transition: {} → {} — ignoring device status",
                    machine.getMachineCode(), current, incoming);
            return false;
        }

        log.info("[{}] State transition: {} → {}", machine.getMachineCode(), current, incoming);
        machine.setStatus(incoming);
        return true;
    }
}
