package com.laundry.websocket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.runtime.MachineRuntimeState;
import com.laundry.machine.service.MachineWebSocketBroadcaster;
import com.laundry.machine.runtime.MachineRuntimeStateService;
import com.laundry.websocket.dto.RuntimeSnapshotMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Real implementation of MachineWebSocketBroadcaster.
 *
 * <p>Broadcasts machine state updates and realtime telemetry to
 * WebSocket/STOMP clients.
 *
 * <p>Topics:
 * <ul>
 *     <li>{@code /topic/machines}               — all machine updates (broadcast)</li>
 *     <li>{@code /topic/machines/{machineCode}}  — specific machine updates</li>
 *     <li>{@code /topic/machines/{machineCode}/runtime}    — full realtime runtime snapshot</li>
 *     <li>{@code /topic/branches/{branchId}/machines/{machineCode}/runtime} — branch-scoped</li>
 * </ul>
 */
@Service("machineWebSocketService")
@RequiredArgsConstructor
@Slf4j
public class MachineWebSocketService implements MachineWebSocketBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final MachineRuntimeStateService runtimeStateService;

    @Override
    public void broadcastMachineUpdate(Machine machine) {
        broadcastMachineUpdate(machine, null, null);
    }

    @Override
    public void broadcastMachineUpdate(Machine machine, Boolean doorClosed, Boolean leakDetected) {
        MachineStatusMessage msg = buildStatusMessage(machine, doorClosed, leakDetected);

        String machineTopic = "/topic/machines/" + machine.getMachineCode();
        messagingTemplate.convertAndSend(machineTopic, msg);
        messagingTemplate.convertAndSend("/topic/machines", msg);

        log.debug("[WebSocket] Broadcast machine update: {} → {} (online={}) on {}",
                machine.getMachineCode(), machine.getStatus(), machine.isOnline(), machineTopic);
    }

    /**
     * Full-fidelity realtime push of the merged runtime snapshot. This is the
     * canonical path for the Machine Detail UI: it carries every field the
     * firmware is sending so the frontend does not need to make additional
     * REST calls between MQTT messages.
     */
    @Override
    public void broadcastRuntimeSnapshot(Machine machine, MachineRuntimeState snapshot) {
        if (machine == null || snapshot == null) {
            return;
        }
        RuntimeSnapshotMessage msg = RuntimeSnapshotMessage.from(machine, snapshot);

        String machineTopic = "/topic/machines/" + machine.getMachineCode() + "/runtime";
        messagingTemplate.convertAndSend(machineTopic, msg);

        // Branch-scoped topic so a kiosk / dashboard for branch X never
        // sees branch Y.
        if (machine.getBranchId() != null) {
            String branchTopic = "/topic/branches/" + machine.getBranchId()
                    + "/machines/" + machine.getMachineCode() + "/runtime";
            messagingTemplate.convertAndSend(branchTopic, msg);
        }

        // Compact global fan-out for the machines list view.
        messagingTemplate.convertAndSend("/topic/machines", msg);

        log.debug("[REALTIME PUSH] machine={} seq={}", machine.getMachineCode(), msg.seq());
    }

    @Override
    public void broadcastTelemetry(String machineCode, BigDecimal weight, BigDecimal waterLevel,
                                   BigDecimal vibration, Integer remainingSeconds) {
        TelemetryMessage msg = new TelemetryMessage(
                machineCode,
                weight,
                waterLevel,
                vibration,
                remainingSeconds != null ? remainingSeconds : 0,
                OffsetDateTime.now()
        );

        String topic = "/topic/machines/" + machineCode + "/telemetry";
        messagingTemplate.convertAndSend(topic, msg);

        log.debug("[WebSocket] Broadcast telemetry: {} weight={}kg remaining={}s",
                machineCode, weight, remainingSeconds);
    }

    // ── Message DTOs (kept inner so legacy callers stay compiled) ───────────

    /**
     * Carries the persistent machine state + the latest known runtime snapshot.
     * Frontends can read either the top-level fields or the {@code runtime}
     * block — they always agree because we derive them from the same source.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MachineStatusMessage(
            UUID machineId,
            String machineCode,
            UUID branchId,
            String status,
            String phase,
            Boolean running,
            Boolean available,
            Boolean error,
            String errorMessage,
            boolean online,
            boolean doorClosed,
            boolean leakDetected,
            String errorCode,
            String displayName,
            Integer wifiRssi,
            Long uptimeSeconds,
            OffsetDateTime lastHeartbeatAt,
            OffsetDateTime timestamp
    ) {}

    public record TelemetryMessage(
            String machineCode,
            BigDecimal weight,
            BigDecimal waterLevel,
            BigDecimal vibration,
            int remainingSeconds,
            OffsetDateTime timestamp
    ) {}

    private static final java.util.Set<String> RUNNING_PHASES = java.util.Set.of("CHECKING", "WASHING", "SPINNING");

    /**
     * The DB only stores RUNNING while the device reports the cycle phase
     * (CHECKING / WASHING / SPINNING) on the runtime topic. Send the phase here
     * too, otherwise the two pushes alternate and the UI badge flickers.
     */
    private static String displayStatus(Machine m, MachineRuntimeState runtime) {
        if (m.getStatus() == null) return "AVAILABLE";
        String runtimeStatus = runtime != null ? runtime.getStatus() : null;
        if (m.getStatus() == com.laundry.machine.entity.MachineStatus.RUNNING
                && runtimeStatus != null && RUNNING_PHASES.contains(runtimeStatus.toUpperCase())) {
            return runtimeStatus.toUpperCase();
        }
        return m.getStatus().name();
    }

    private MachineStatusMessage buildStatusMessage(Machine m, Boolean doorClosed, Boolean leakDetected) {
        MachineRuntimeState runtime = runtimeStateService.get(m.getId()).orElse(null);

        Boolean resolvedDoorClosed = doorClosed;
        if (resolvedDoorClosed == null && runtime != null) resolvedDoorClosed = runtime.getDoorClosed();
        if (resolvedDoorClosed == null) resolvedDoorClosed = true;

        Boolean resolvedLeak = leakDetected;
        if (resolvedLeak == null && runtime != null) resolvedLeak = runtime.getLeakDetected();
        if (resolvedLeak == null) resolvedLeak = false;

        return new MachineStatusMessage(
                m.getId(),
                m.getMachineCode(),
                m.getBranchId(),
                displayStatus(m, runtime),
                runtime != null ? runtime.getPhase() : null,
                runtime != null ? runtime.getRunning() : null,
                runtime != null ? runtime.getAvailable() : null,
                runtime != null ? runtime.getError() : null,
                runtime != null ? runtime.getErrorMessage() : null,
                m.isOnline(),
                resolvedDoorClosed,
                resolvedLeak,
                m.getErrorCode(),
                m.getDisplayName(),
                runtime != null ? runtime.getWifiRssi() : null,
                runtime != null ? runtime.getUptimeSeconds() : null,
                m.getLastHeartbeatAt(),
                OffsetDateTime.now()
        );
    }
}