package com.laundry.mqtt.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.iot.service.CommissioningService;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.runtime.MachineRuntimeState;
import com.laundry.machine.runtime.MachineRuntimeStateService;
import com.laundry.machine.service.MachineService;
import com.laundry.machine.service.MachineWebSocketBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Handles MQTT messages from washer devices in a branch-scoped architecture.
 *
 * <p>Responsibilities:
 * <ol>
 *     <li>Parse JSON payloads into typed DTOs (best-effort, never throws).</li>
 *     <li>Resolve the machine once per message (single SQL lookup).</li>
 *     <li>Update the in-memory {@link MachineRuntimeStateService} snapshot.</li>
 *     <li>For non-telemetry channels, drive persistent state changes
 *         through {@link MachineService} (which is the only place that
 *         writes to PostgreSQL).</li>
 *     <li>Broadcast realtime updates over WebSocket/STOMP.</li>
 * </ol>
 *
 * <p>The handler is intentionally side-effect-safe: an unparseable payload
 * or an unknown (branchId, machineId) tuple logs a WARN and returns — never
 * throws. This is critical because we are running on the Paho network thread
 * (via {@code @Async("mqttTaskExecutor")}) and a crash would silently break
 * the entire MQTT pipeline.
 *
 * <p>Channel semantics:
 * <ul>
 *     <li><b>status</b> — authoritative business status + LWT OFFLINE.
 *         Persists to {@code machines} ONLY on persistent state change.</li>
 *     <li><b>telemetry</b> — comprehensive runtime snapshot. NO persistent
 *         write; only in-memory state + WebSocket broadcast.</li>
 *     <li><b>heartbeat</b> — connectivity liveness. Updates in-memory state
 *         and {@code machines.online} + {@code last_heartbeat_at} via a
 *         single bulk JPQL UPDATE.</li>
 *     <li><b>event</b> — audit/history only. Persists a {@code device_events}
 *         row but does NOT drive Machine.status (telemetry/status do that).</li>
 *     <li><b>command/ack</b> — matches ACK to in-flight {@link DeviceCommand}
 *         and updates its status. NO Machine.status write.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WasherMessageHandler {

    private final ObjectMapper objectMapper;
    private final MachineService machineService;
    private final MachineRuntimeStateService runtimeStateService;
    private final MachineRepository machineRepository;
    private final MachineWebSocketBroadcaster broadcaster;
    private final CommissioningService commissioningService;

    // ── /status ────────────────────────────────────────────────────────────

    /**
     * Handle {@code laundry/washer/{branchId-UUID}/{machineId}/status}.
     *
     * <p>Persists a {@code Machine} row only when the business status
     * actually transitions. Sensor fields are NOT stored on the entity —
     * they live in the runtime snapshot.
     */
    public void handleStatus(UUID branchId, String machineId, String payload) {
        WasherPayloads.StatusPayload data = parse(payload, WasherPayloads.StatusPayload.class);
        if (data == null) return;

        Machine machine = resolveMachineOrWarn(branchId, machineId);
        if (machine == null) return;

        log.debug("[MQTT RX] topic=laundry/washer/{}/{} channel=status seq={} status={} online={}",
                branchId, machineId, data.getSeq(), data.getStatus(), data.getOnline());

        MachineRuntimeState snapshot = data.toRuntimeSnapshot();
        runtimeStateService.applyStatus(machine, snapshot);

        boolean treatAsOffline = Boolean.FALSE.equals(data.getOnline())
                || "OFFLINE".equalsIgnoreCase(safe(data.getStatus()));

        if (treatAsOffline) {
            machineService.markOfflineFromMqtt(branchId, machineId,
                    safe(data.getStatus()), snapshot.getErrorMessage());
            return;
        }

        machineService.processStatus(branchId, machineId, snapshot);

        // Re-emit the merged snapshot to subscribers so the UI sees the
        // authoritative /status immediately, even if no /telemetry follows.
        MachineRuntimeState merged = runtimeStateService.get(machine.getId()).orElse(snapshot);
        broadcaster.broadcastRuntimeSnapshot(machine, merged);
    }

    // ── /telemetry ─────────────────────────────────────────────────────────

    /**
     * Handle {@code laundry/washer/{branchId-UUID}/{machineId}/telemetry}.
     *
     * <p>This is the highest-frequency channel. The handler does NOT touch
     * PostgreSQL: the runtime snapshot lives in memory + a scheduled
     * JSONB flusher. Only the in-memory snapshot + WebSocket broadcast
     * happen synchronously.
     */
    public void handleTelemetry(UUID branchId, String machineId, String payload) {
        WasherPayloads.TelemetryPayload data = parse(payload, WasherPayloads.TelemetryPayload.class);
        if (data == null) return;

        Machine machine = resolveMachineOrWarn(branchId, machineId);
        if (machine == null) return;

        log.debug("[MQTT RX] topic=laundry/washer/{}/{} branchId={} machineId={} channel=telemetry seq={} fw={} sim={}",
                branchId, machineId, branchId, machineId, data.getSeq(), data.getFirmwareVersion(), data.getSimulation());

        MachineRuntimeState snapshot = data.toRuntimeSnapshot();
        runtimeStateService.applyTelemetry(machine, snapshot);

        // Promote business status changes to the DB write path. Sensor fields
        // remain in the runtime snapshot only.
        if (snapshot.getStatus() != null) {
            machineService.processStatusFromRuntime(branchId, machineId, snapshot);
        }

        // Pull the merged state back out so the WebSocket payload reflects
        // every field, including ones merged from previous /status / heartbeat
        // messages.
        MachineRuntimeState merged = runtimeStateService.get(machine.getId()).orElse(snapshot);
        broadcaster.broadcastRuntimeSnapshot(machine, merged);

        log.debug("[RUNTIME UPDATE] machine={} seq={} status={} door={} weight={}kg water={}L vibration={}g",
                machine.getMachineCode(), merged.getLastTelemetrySeq(), merged.getStatus(),
                merged.getDoorClosed(), merged.getWeightKg(), merged.getWaterLevelLiters(), merged.getVibrationG());
    }

    // ── /heartbeat ─────────────────────────────────────────────────────────

    /**
     * Handle {@code laundry/washer/{branchId-UUID}/{machineId}/heartbeat}.
     *
     * <p>Firmware sends every ~10 s. Update the in-memory snapshot and
     * touch {@code machines.online} + {@code last_heartbeat_at} via a
     * single bulk JPQL UPDATE — never load the full entity.
     */
    public void handleHeartbeat(UUID branchId, String machineId, String payload) {
        WasherPayloads.HeartbeatPayload data = parse(payload, WasherPayloads.HeartbeatPayload.class);
        if (data == null) return;

        Machine machine = resolveMachineOrWarn(branchId, machineId);
        if (machine == null) return;

        log.debug("[MQTT RX] topic=laundry/washer/{}/{} channel=heartbeat seq={} fw={}",
                branchId, machineId, data.getSeq(), data.getFirmwareVersion());

        MachineRuntimeState snapshot = data.toRuntimeSnapshot();
        runtimeStateService.applyHeartbeat(machine, snapshot);

        machineService.processHeartbeat(branchId, machineId, snapshot);
    }

    // ── /event ─────────────────────────────────────────────────────────────

    /**
     * Handle {@code laundry/washer/{branchId-UUID}/{machineId}/event}.
     *
     * <p>Events are recorded for audit/history. They do NOT drive
     * {@code Machine.status} — that is owned by /status and /telemetry.
     */
    public void handleEvent(UUID branchId, String machineId, String payload) {
        if (handleSelfTestResult(branchId, machineId, payload)) return;

        WasherPayloads.EventPayload data = parse(payload, WasherPayloads.EventPayload.class);
        if (data == null) return;

        Machine machine = resolveMachineOrWarn(branchId, machineId);
        if (machine == null) return;

        MachineRuntimeState snapshot = data.toRuntimeSnapshot();
        runtimeStateService.applyEvent(machine, snapshot);

        machineService.processDeviceEvent(branchId, machineId, snapshot,
                data.getEvent(), data.getDetail(), data.getBookingId());
    }

    /**
     * Commissioning self-test report: {@code {"event":"SELF_TEST_RESULT","commandId":..,"passed":..,"checks":{..}}}.
     * Returns true when the payload was a self-test result (handled or ignored).
     */
    private boolean handleSelfTestResult(UUID branchId, String machineId, String payload) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            return false;
        }
        if (node == null || !"SELF_TEST_RESULT".equalsIgnoreCase(node.path("event").asText(""))) {
            return false;
        }
        commissioningService.handleSelfTestResult(branchId, machineId,
                node.path("commandId").asText(null),
                node.path("passed").asBoolean(false),
                node.path("checks").toString());
        return true;
    }

    // ── /command/ack ───────────────────────────────────────────────────────

    /**
     * Handle {@code laundry/washer/{branchId-UUID}/{machineId}/command/ack}.
     */
    public void handleCommandAck(UUID branchId, String machineId, String payload) {
        WasherPayloads.CommandAckPayload data = parse(payload, WasherPayloads.CommandAckPayload.class);
        if (data == null) return;

        Machine machine = resolveMachineOrWarn(branchId, machineId);
        if (machine == null) return;

        MachineRuntimeState snapshot = data.toRuntimeSnapshot();
        runtimeStateService.applyEvent(machine, snapshot);

        machineService.processCommandAck(branchId, machineId, data.correlationId(),
                data.accepted(), data.getResult(), data.getMessage(), data.getReason());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Resolve {@code (branchId, machineId)} → {@link Machine} exactly once.
     * Logs {@code UNKNOWN_MQTT_MACHINE} and returns {@code null} on miss so
     * the caller can short-circuit. Uses a hot-path JPA lookup; the JPA
     * persistence context only carries this one row for the duration of
     * the handler invocation.
     */
    private Machine resolveMachineOrWarn(UUID branchId, String machineId) {
        try {
            return machineRepository.findByBranchIdAndMachineCode(branchId, machineId)
                    .orElseThrow(() -> {
                        log.warn("UNKNOWN_MQTT_MACHINE: branchId={} machineId={} — no registry match",
                                branchId, machineId);
                        return new MachineNotFoundException(
                                "Unknown MQTT machine: branchId=" + branchId + " machineId=" + machineId);
                    });
        } catch (MachineNotFoundException e) {
            return null;
        }
    }

    private <T> T parse(String payload, Class<T> clazz) {
        try {
            return objectMapper.readValue(payload, clazz);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse MQTT payload as {}: {} — payload: {}",
                    clazz.getSimpleName(), e.getMessage(),
                    payload != null && payload.length() > 200
                            ? payload.substring(0, 200) + "..."
                            : payload);
            return null;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}