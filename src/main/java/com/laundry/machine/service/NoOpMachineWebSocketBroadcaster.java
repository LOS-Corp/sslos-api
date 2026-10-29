package com.laundry.machine.service;

import com.laundry.machine.entity.Machine;
import com.laundry.machine.runtime.MachineRuntimeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * No-op implementation of MachineWebSocketBroadcaster.
 */
@Component
@ConditionalOnMissingBean(name = "machineWebSocketService")
@Slf4j
public class NoOpMachineWebSocketBroadcaster implements MachineWebSocketBroadcaster {

    @Override
    public void broadcastMachineUpdate(Machine machine) {
        log.debug("[NoOp WebSocket] Machine update: {} → {} (online={})",
                machine.getMachineCode(), machine.getStatus(), machine.isOnline());
    }

    @Override
    public void broadcastMachineUpdate(Machine machine, Boolean doorClosed, Boolean leakDetected) {
        log.debug("[NoOp WebSocket] Machine update: {} → {} (online={}, doorClosed={}, leak={})",
                machine.getMachineCode(), machine.getStatus(), machine.isOnline(), doorClosed, leakDetected);
    }

    @Override
    public void broadcastRuntimeSnapshot(Machine machine, MachineRuntimeState snapshot) {
        log.debug("[NoOp WebSocket] Runtime snapshot: {} seq={} status={} door={} weight={}kg",
                machine.getMachineCode(),
                snapshot == null ? null : snapshot.getLastTelemetrySeq(),
                snapshot == null ? null : snapshot.getStatus(),
                snapshot == null ? null : snapshot.getDoorClosed(),
                snapshot == null ? null : snapshot.getWeightKg());
    }

    @Override
    public void broadcastTelemetry(String machineCode, BigDecimal weight, BigDecimal waterLevel,
                                   BigDecimal vibration, Integer remainingSeconds) {
        log.debug("[NoOp WebSocket] Telemetry: {} weight={}kg remaining={}s",
                machineCode, weight, remainingSeconds);
    }
}
