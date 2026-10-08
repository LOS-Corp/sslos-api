package com.laundry.machine.service;

import com.laundry.machine.entity.Machine;
import com.laundry.machine.runtime.MachineRuntimeState;

import java.math.BigDecimal;

/**
 * Interface for broadcasting machine state changes and realtime telemetry
 * to WebSocket/STOMP clients.
 *
 * <p>Decouples MachineService from WebSocket infrastructure.
 */
public interface MachineWebSocketBroadcaster {

    /**
     * Broadcast a machine status/state update.
     * Destination: /topic/machines and /topic/machines/{machineCode}
     */
    void broadcastMachineUpdate(Machine machine);

    /**
     * Broadcast a machine status update with optional realtime door & leak flags.
     */
    void broadcastMachineUpdate(Machine machine, Boolean doorClosed, Boolean leakDetected);

    /**
     * Broadcast the full runtime snapshot to a machine-specific topic.
     * This is the canonical realtime push — it carries the merged
     * telemetry / status / heartbeat / event state and is delivered to
     * every STOMP subscriber within a few hundred milliseconds of the
     * MQTT message arriving.
     *
     * <p>Destinations:
     * <ul>
     *   <li>{@code /topic/machines/{machineCode}/runtime} — machine-scoped</li>
     *   <li>{@code /topic/branches/{branchId}/machines/{machineCode}/runtime} — branch-scoped</li>
     *   <li>{@code /topic/machines} — global fan-out (compact view)</li>
     * </ul>
     */
    void broadcastRuntimeSnapshot(Machine machine, MachineRuntimeState snapshot);

    /**
     * Legacy convenience broadcast — kept so callers passing only a few
     * derived fields continue to work. Prefer {@link #broadcastRuntimeSnapshot}
     * for full-fidelity realtime push.
     */
    void broadcastTelemetry(String machineCode, BigDecimal weight, BigDecimal waterLevel,
                            BigDecimal vibration, Integer remainingSeconds);
}
