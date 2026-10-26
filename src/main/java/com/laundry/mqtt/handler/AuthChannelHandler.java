package com.laundry.mqtt.handler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.locker.service.LockerService;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineStartAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles the {@code /auth} channel (QR check-in + physical START) and forwards
 * each message type to {@link MachineStartAuthorizationService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthChannelHandler {

    private final ObjectMapper objectMapper;
    private final MachineRepository machineRepository;
    private final MachineStartAuthorizationService authorizationService;
    private final LockerService lockerService;

    /**
     * One shape for every {@code /auth} message; {@code type} says which fields are set.
     *
     * <ul>
     *   <li>{@code AUTH_ACK} — device answer to {@code AUTHORIZE_START}.</li>
     *   <li>{@code START_REQUEST} — customer pressed START on an authorized machine.</li>
     *   <li>{@code STARTED} — the cycle is actually running.</li>
     *   <li>{@code START_REJECTED} — device refused to start (door open, interlock, ...).</li>
     *   <li>{@code START_FAILED} — device accepted, then failed while starting.</li>
     *   <li>{@code AUTH_EXPIRED} — local timer ran out before START was pressed.</li>
     *   <li>Locker cabinets: {@code PIN_ENTERED}, {@code COMPARTMENT_CLOSED}, {@code OPEN_FAILED}
     *       (see {@link LockerService}).</li>
     * </ul>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AuthMessage(
            String type,
            UUID authorizationId,
            String commandId,
            String startRequestId,
            UUID runId,
            Boolean success,
            String result,
            String reason,
            String bootId,
            String requestId,
            String pin,
            String accessId,
            Integer compartment,
            Boolean opened
    ) {
    }

    public void handle(UUID branchId, String machineCode, String payload) {
        AuthMessage message = parse(payload);
        if (message == null) {
            return;
        }
        Optional<Machine> machine = machineRepository.findByBranchIdAndMachineCode(branchId, machineCode);
        if (machine.isEmpty()) {
            log.warn("Auth message for unknown machine branchId={} machineId={}", branchId, machineCode);
            return;
        }
        dispatch(machine.get(), message);
    }

    private void dispatch(Machine machine, AuthMessage m) {
        String type = m.type() == null ? "" : m.type().trim().toUpperCase(Locale.ROOT);
        if (machine.getType() == MachineType.LOCKER) {
            dispatchLocker(machine, type, m);
            return;
        }
        switch (type) {
            case "AUTH_ACK" -> authorizationService.handleAuthAck(machine,
                    m.authorizationId(), m.commandId(), Boolean.TRUE.equals(m.success()),
                    orEmpty(m.result()), orEmpty(m.reason()), orEmpty(m.bootId()));
            case "START_REQUEST" -> authorizationService.handleStartRequest(machine,
                    m.authorizationId(), orEmpty(m.startRequestId()), orEmpty(m.bootId()));
            case "STARTED" -> authorizationService.handleStarted(machine,
                    m.authorizationId(), orEmpty(m.startRequestId()), m.runId(), orEmpty(m.bootId()));
            case "START_REJECTED" -> authorizationService.handleStartRejected(machine,
                    m.authorizationId(), orEmpty(m.startRequestId()), orEmpty(m.reason()), orEmpty(m.bootId()));
            case "START_FAILED" -> authorizationService.handleStartFailed(machine,
                    m.authorizationId(), orEmpty(m.startRequestId()), orEmpty(m.reason()), orEmpty(m.bootId()));
            case "AUTH_EXPIRED" -> authorizationService.handleAuthExpired(machine,
                    m.authorizationId(), orEmpty(m.bootId()));
            default -> log.warn("Unknown auth message type '{}' from machine {}", m.type(), machine.getMachineCode());
        }
    }

    private void dispatchLocker(Machine machine, String type, AuthMessage m) {
        switch (type) {
            case "PIN_ENTERED" -> lockerService.handlePinEntered(machine, m.requestId(), m.compartment(), m.pin());
            case "COMPARTMENT_CLOSED" -> lockerService.handleCompartmentClosed(machine, m.accessId(),
                    m.compartment(), Boolean.TRUE.equals(m.opened()));
            case "OPEN_FAILED" -> lockerService.handleOpenFailed(machine, m.accessId(), orEmpty(m.reason()));
            default -> log.warn("Unknown locker auth message type '{}' from {}", m.type(), machine.getMachineCode());
        }
    }

    private AuthMessage parse(String payload) {
        try {
            return objectMapper.readValue(payload, AuthMessage.class);
        } catch (JsonProcessingException e) {
            log.warn("Cannot parse auth payload: {} — payload: {}", e.getOriginalMessage(), payload);
            return null;
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
