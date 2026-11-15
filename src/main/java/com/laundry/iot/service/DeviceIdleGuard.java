package com.laundry.iot.service;

import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Refuses board maintenance (firmware flashing, Wi-Fi reset) while a customer is using its machine:
 * it would kill the cycle or strand the customer's start authorization.
 */
@Component
public class DeviceIdleGuard {

    /** A customer is using the machine. */
    static final Set<MachineStatus> BUSY = EnumSet.of(
            MachineStatus.RESERVED, MachineStatus.WAITING_START, MachineStatus.RUNNING, MachineStatus.PAUSED);

    private final MachineRepository machineRepository;
    private final MachineStartAuthorizationRepository startAuthorizationRepository;

    public DeviceIdleGuard(MachineRepository machineRepository,
                           MachineStartAuthorizationRepository startAuthorizationRepository) {
        this.machineRepository = machineRepository;
        this.startAuthorizationRepository = startAuthorizationRepository;
    }

    /** @throws ProvisioningRejectedException 409 when the board's machine has a customer session */
    public void requireIdle(IotDevice device) {
        if (device.getMachineId() == null) return;
        Machine machine = machineRepository.findById(device.getMachineId()).orElse(null);
        if (machine == null) return;
        if (BUSY.contains(machine.getStatus())) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "Machine " + machine.getMachineCode()
                    + " is " + machine.getStatus() + "; wait for the customer session to end");
        }
        if (startAuthorizationRepository.findActiveByMachine(machine.getId()).isPresent()) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "Machine " + machine.getMachineCode()
                    + " has a pending start authorization");
        }
    }
}
