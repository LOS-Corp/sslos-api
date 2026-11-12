package com.laundry.machine.service;

import com.laundry.machine.entity.DeviceCommandStatus;
import com.laundry.machine.repository.DeviceCommandRepository;
import com.laundry.devicelink.DeviceLink;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.repository.MachineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;

/**
 * Sends a stored command to its device after the issuing transaction committed (ADR-08), then
 * records SENT or FAILED in its own transaction. A command whose ACK never arrives is turned into
 * TIMEOUT by the command-timeout job (BR-31).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceCommandDispatcher {

    private final DeviceLink deviceLink;
    private final MachineRepository machines;
    private final DeviceCommandRepository commands;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(DeviceCommandRequested event) {
        commands.findById(event.commandId()).ifPresent(command -> {
            DeviceCommandRequested.Payload payload = event.payload();
            try {
                Machine machine = machines.findByBranchIdAndMachineCode(payload.branchId(), payload.machineCode())
                        .orElseThrow(() -> new IllegalStateException("Machine " + payload.machineCode() + " not found"));
                deviceLink.send(machine, payload.json());
                command.setStatus(DeviceCommandStatus.SENT);
                command.setSentAt(OffsetDateTime.now());
                log.info("[{}] Command {} sent", payload.machineCode(), command.getCommandId());
            } catch (RuntimeException e) {
                command.setStatus(DeviceCommandStatus.FAILED);
                command.setAckReason("Send failed: " + e.getMessage());
                log.error("[{}] Failed to send command {}: {}", payload.machineCode(), command.getCommandId(),
                        e.getMessage());
            }
            commands.save(command);
        });
    }
}
