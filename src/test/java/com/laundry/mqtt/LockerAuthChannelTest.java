package com.laundry.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.locker.service.LockerService;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineStartAuthorizationService;
import com.laundry.mqtt.handler.AuthChannelHandler;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LockerAuthChannelTest {

    private static final UUID BRANCH = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final MachineRepository machines = mock(MachineRepository.class);
    private final MachineStartAuthorizationService authorizations = mock(MachineStartAuthorizationService.class);
    private final LockerService lockers = mock(LockerService.class);
    private final AuthChannelHandler handler = new AuthChannelHandler(new ObjectMapper(), machines, authorizations, lockers);

    private Machine machine(String code, MachineType type) {
        Machine machine = Machine.builder().id(UUID.randomUUID()).machineCode(code).branchId(BRANCH).type(type).build();
        when(machines.findByBranchIdAndMachineCode(BRANCH, code)).thenReturn(Optional.of(machine));
        return machine;
    }

    @Test
    void lockerMessagesGoToTheLockerService() {
        Machine cabinet = machine("LK001", MachineType.LOCKER);

        handler.handle(BRANCH, "LK001", "{\"type\":\"PIN_ENTERED\",\"requestId\":\"r1\",\"compartment\":2,\"pin\":\"123456\"}");
        verify(lockers).handlePinEntered(cabinet, "r1", 2, "123456");

        handler.handle(BRANCH, "LK001", "{\"type\":\"COMPARTMENT_CLOSED\",\"accessId\":\"a1\",\"compartment\":2,\"opened\":true}");
        verify(lockers).handleCompartmentClosed(cabinet, "a1", 2, true);

        handler.handle(BRANCH, "LK001", "{\"type\":\"OPEN_FAILED\",\"accessId\":\"a2\",\"reason\":\"BUSY\"}");
        verify(lockers).handleOpenFailed(cabinet, "a2", "BUSY");
        verifyNoInteractions(authorizations);
    }

    @Test
    void washersKeepTheStartProtocol() {
        machine("WM001", MachineType.WASHER);
        handler.handle(BRANCH, "WM001", "{\"type\":\"PIN_ENTERED\",\"pin\":\"123456\"}");
        verifyNoInteractions(lockers);
        handler.handle(BRANCH, "WM001", "{\"type\":\"AUTH_EXPIRED\",\"bootId\":\"b\"}");
        verify(authorizations).handleAuthExpired(any(), any(), any());
    }
}
