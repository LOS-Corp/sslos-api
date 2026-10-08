package com.laundry.mqtt.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineStartAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Adapter between the MQTT {@code /auth} channel payload format and the
 * {@link MachineStartAuthorizationService} domain service. Keeps
 * {@link WasherMessageHandler} small and lets us unit-test the auth
 * flow without spinning up an MQTT broker.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthChannelHandler {

    private final ObjectMapper objectMapper;
    private final MachineRepository machineRepository;
    private final MachineStartAuthorizationService authorizationService;

    /**
     * Dispatch an incoming {@code /auth} payload to the correct service method.
     * The discriminator is the {@code type} field on the payload.
     */
    public void handle(UUID branchId, String machineId, String payload) {
        AuthChannelPayloads.AuthAckPayload ack = tryParse(payload, AuthChannelPayloads.AuthAckPayload.class);
        if (ack != null && "AUTH_ACK".equalsIgnoreCase(ack.getType())) {
            apply(machineId, m -> authorizationService.handleAuthAck(
                    m,
                    ack.getAuthorizationId(),
                    ack.getCommandId(),
                    Boolean.TRUE.equals(ack.getSuccess()),
                    safe(ack.getResult()),
                    safe(ack.getReason()),
                    safe(ack.getBootId())));
            return;
        }

        AuthChannelPayloads.StartRequestPayload req = tryParse(payload, AuthChannelPayloads.StartRequestPayload.class);
        if (req != null && "START_REQUEST".equalsIgnoreCase(req.getType())) {
            apply(machineId, m -> authorizationService.handleStartRequest(
                    m,
                    req.getAuthorizationId(),
                    safe(req.getStartRequestId()),
                    safe(req.getBootId())));
            return;
        }

        AuthChannelPayloads.StartedPayload started = tryParse(payload, AuthChannelPayloads.StartedPayload.class);
        if (started != null && "STARTED".equalsIgnoreCase(started.getType())) {
            apply(machineId, m -> authorizationService.handleStarted(
                    m,
                    started.getAuthorizationId(),
                    safe(started.getStartRequestId()),
                    started.getRunId(),
                    safe(started.getBootId())));
            return;
        }

        AuthChannelPayloads.StartRejectedPayload rej = tryParse(payload, AuthChannelPayloads.StartRejectedPayload.class);
        if (rej != null && "START_REJECTED".equalsIgnoreCase(rej.getType())) {
            apply(machineId, m -> authorizationService.handleStartRejected(
                    m,
                    rej.getAuthorizationId(),
                    safe(rej.getStartRequestId()),
                    safe(rej.getReason()),
                    safe(rej.getBootId())));
            return;
        }

        AuthChannelPayloads.StartFailedPayload failed = tryParse(payload, AuthChannelPayloads.StartFailedPayload.class);
        if (failed != null && "START_FAILED".equalsIgnoreCase(failed.getType())) {
            apply(machineId, m -> authorizationService.handleStartFailed(
                    m,
                    failed.getAuthorizationId(),
                    safe(failed.getStartRequestId()),
                    safe(failed.getReason()),
                    safe(failed.getBootId())));
            return;
        }

        AuthChannelPayloads.AuthExpiredPayload exp = tryParse(payload, AuthChannelPayloads.AuthExpiredPayload.class);
        if (exp != null && "AUTH_EXPIRED".equalsIgnoreCase(exp.getType())) {
            apply(machineId, m -> authorizationService.handleAuthExpired(
                    m,
                    exp.getAuthorizationId(),
                    safe(exp.getBootId())));
            return;
        }

        log.warn("Unrecognised auth-channel payload for machine={}: {}", machineId, payload);
    }

    private void apply(String machineCode, java.util.function.Consumer<Machine> action) {
        Machine machine = machineRepository.findByMachineCode(machineCode).orElse(null);
        if (machine == null) {
            log.warn("Auth event for unknown machine: {}", machineCode);
            return;
        }
        try {
            action.accept(machine);
        } catch (RuntimeException e) {
            log.error("Auth event handler threw for machine {}: {}", machineCode, e.getMessage(), e);
        }
    }

    private <T> T tryParse(String payload, Class<T> clazz) {
        try {
            return objectMapper.readValue(payload, clazz);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
