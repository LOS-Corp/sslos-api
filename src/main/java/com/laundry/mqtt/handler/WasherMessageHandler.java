package com.laundry.mqtt.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.iot.service.CommissioningService;
import com.laundry.locker.service.LockerService;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.runtime.MachineRuntimeState;
import com.laundry.machine.runtime.MachineRuntimeStateService;
import com.laundry.machine.service.MachineService;
import com.laundry.machine.service.MachineWebSocketBroadcaster;
import com.laundry.mqtt.handler.WasherPayloads.CommandAckPayload;
import com.laundry.mqtt.handler.WasherPayloads.DeviceSnapshot;
import com.laundry.mqtt.handler.WasherPayloads.EventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Handles washer device channels.
 *
 * <p>Each message updates the in-memory runtime state; database writes go through
 * {@link MachineService} only. An unparseable payload or an unknown machine is logged
 * and dropped.
 *
 * <ul>
 *   <li>{@code status} — business status, also the retained Last Will OFFLINE message.</li>
 *   <li>{@code telemetry} — full runtime snapshot, the most frequent channel.</li>
 *   <li>{@code heartbeat} — liveness, updates {@code online} and {@code last_heartbeat_at}.</li>
 *   <li>{@code event} — recorded for audit/history, does not drive the machine status.</li>
 *   <li>{@code command/ack} — closes the matching {@code DeviceCommand}.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WasherMessageHandler {

    private static final int MAX_LOGGED_PAYLOAD = 200;

    private final ObjectMapper objectMapper;
    private final MachineRepository machineRepository;
    private final MachineService machineService;
    private final MachineRuntimeStateService runtimeStateService;
    private final MachineWebSocketBroadcaster broadcaster;
    private final CommissioningService commissioningService;
    private final LockerService lockerService;

    public void handleStatus(UUID branchId, String machineCode, String payload) {
        withMachine(branchId, machineCode, payload, DeviceSnapshot.class, (machine, data) -> {
            MachineRuntimeState state = data.toRuntimeState();
            runtimeStateService.applyStatus(machine, state);

            if (data.isOffline()) {
                machineService.markOfflineFromMqtt(branchId, machineCode,
                        data.trimmedStatus(), state.getErrorMessage());
                return;
            }
            machineService.processStatus(branchId, machineCode, state);
            if (machine.getType() == MachineType.LOCKER) lockerService.recordSnapshot(machine, payload);
            broadcastMergedState(machine, state);
        });
    }

    public void handleTelemetry(UUID branchId, String machineCode, String payload) {
        withMachine(branchId, machineCode, payload, DeviceSnapshot.class, (machine, data) -> {
            MachineRuntimeState state = data.toRuntimeState();
            runtimeStateService.applyTelemetry(machine, state);

            if (state.getStatus() != null) {
                machineService.processStatusFromRuntime(branchId, machineCode, state);
            }
            if (machine.getType() == MachineType.LOCKER) lockerService.recordSnapshot(machine, payload);
            broadcastMergedState(machine, state);
        });
    }

    public void handleHeartbeat(UUID branchId, String machineCode, String payload) {
        withMachine(branchId, machineCode, payload, DeviceSnapshot.class, (machine, data) -> {
            MachineRuntimeState state = data.toRuntimeState();
            runtimeStateService.applyHeartbeat(machine, state);
            machineService.processHeartbeat(branchId, machineCode, state);
        });
    }

    public void handleEvent(UUID branchId, String machineCode, String payload) {
        EventPayload data = parse(payload, EventPayload.class);
        if (data == null) {
            return;
        }

        if (data.isSelfTestResult()) {
            commissioningService.handleSelfTestResult(branchId, machineCode, data.commandId(),
                    Boolean.TRUE.equals(data.passed()),
                    data.checks() == null ? "" : data.checks().toString());
            return;
        }

        findMachine(branchId, machineCode).ifPresent(machine -> {
            MachineRuntimeState state = data.toRuntimeState();
            runtimeStateService.applyEvent(machine, state);
            machineService.processDeviceEvent(branchId, machineCode, state,
                    data.event(), data.detail(), data.bookingId());
        });
    }

    public void handleCommandAck(UUID branchId, String machineCode, String payload) {
        withMachine(branchId, machineCode, payload, CommandAckPayload.class, (machine, data) -> {
            runtimeStateService.applyEvent(machine, data.toRuntimeState());
            machineService.processCommandAck(branchId, machineCode, data.correlationId(),
                    data.accepted(), data.result(), data.message(), data.reason());
        });
    }

    /** Pushes the merged runtime state, so the UI also sees fields from earlier messages. */
    private void broadcastMergedState(Machine machine, MachineRuntimeState fallback) {
        MachineRuntimeState merged = runtimeStateService.get(machine.getId()).orElse(fallback);
        broadcaster.broadcastRuntimeSnapshot(machine, merged);
    }

    private <T> void withMachine(UUID branchId, String machineCode, String payload,
                                 Class<T> type, BiConsumer<Machine, T> action) {
        T data = parse(payload, type);
        if (data != null) {
            findMachine(branchId, machineCode).ifPresent(machine -> action.accept(machine, data));
        }
    }

    private Optional<Machine> findMachine(UUID branchId, String machineCode) {
        Optional<Machine> machine = machineRepository.findByBranchIdAndMachineCode(branchId, machineCode);
        if (machine.isEmpty()) {
            log.warn("UNKNOWN_MQTT_MACHINE branchId={} machineId={}", branchId, machineCode);
        }
        return machine;
    }

    private <T> T parse(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            log.warn("Cannot parse MQTT payload as {}: {} — payload: {}",
                    type.getSimpleName(), e.getOriginalMessage(), abbreviate(payload));
            return null;
        }
    }

    private static String abbreviate(String payload) {
        return payload.length() > MAX_LOGGED_PAYLOAD ? payload.substring(0, MAX_LOGGED_PAYLOAD) + "..." : payload;
    }
}
