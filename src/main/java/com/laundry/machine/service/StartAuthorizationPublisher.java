package com.laundry.machine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.booking.service.MachineProgramService;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStartAuthorization;
import com.laundry.mqtt.service.MqttPublisher;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Messages of the START handshake sent to the controller (AUTHORIZE_START, START_APPROVAL, REVOKE_START).
 * A failed publish is logged, never thrown: the authorization then expires or times out on its own
 * (BR-08, BR-31). {@link MqttPublisher} is lazy to break the publisher → connection → router → handler
 * → service cycle.
 */
@Slf4j
@Component
public class StartAuthorizationPublisher {

    private final MqttPublisher mqttPublisher;
    private final MachineProgramService programService;
    private final ObjectMapper objectMapper;
    private final int authorizationTtlSeconds;

    public StartAuthorizationPublisher(@Lazy MqttPublisher mqttPublisher,
                                       MachineProgramService programService,
                                       ObjectMapper objectMapper,
                                       @Value("${app.machine.start-authorization.ttl-seconds:120}") int ttlSeconds) {
        this.mqttPublisher = mqttPublisher;
        this.programService = programService;
        this.objectMapper = objectMapper;
        this.authorizationTtlSeconds = ttlSeconds;
    }

    /** AUTHORIZE_START with the program the booking paid for (test runs: the default). */
    public void authorizeStart(Machine machine, MachineStartAuthorization auth) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "AUTHORIZE_START");
        payload.put("commandId", "AUTH-" + auth.getId());
        payload.put("authorizationId", auth.getId().toString());
        if (auth.getBookingId() != null) payload.put("bookingId", auth.getBookingId().toString());
        if (auth.isTestRun()) payload.put("testRun", true);
        payload.put("machineId", machine.getMachineCode());
        payload.put("expiresInSeconds", authorizationTtlSeconds);
        payload.put("issuedAt", OffsetDateTime.now().toString());
        // Without a program the firmware runs its built-in default program.
        if (machine.getType() != null) {
            programService.devicePayload(auth.getTransactionId(), machine.getType().name())
                    .ifPresent(program -> payload.put("program", program));
        }
        send(machine, auth, payload);
    }

    public void startApproval(Machine machine, MachineStartAuthorization auth, String startRequestId, UUID runId,
                              String result, String bootId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "START_APPROVAL");
        payload.put("commandId", "STARTAPP-" + auth.getId());
        payload.put("authorizationId", auth.getId().toString());
        payload.put("startRequestId", startRequestId);
        if (runId != null) payload.put("runId", runId.toString());
        payload.put("result", result);
        payload.put("bootId", bootId);
        payload.put("validForMs", 5000);
        send(machine, auth, payload);
    }

    public void revokeStart(Machine machine, MachineStartAuthorization auth, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "REVOKE_START");
        payload.put("commandId", "REVOKE-" + auth.getId());
        payload.put("authorizationId", auth.getId().toString());
        payload.put("reason", reason);
        send(machine, auth, payload);
    }

    /** Admin test run: press the physical START button remotely. Returns false if it could not be sent. */
    public boolean pressStart(Machine machine, MachineStartAuthorization auth) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "PRESS_START");
        payload.put("commandId", "PRESS-" + auth.getId());
        payload.put("authorizationId", auth.getId().toString());
        payload.put("machineId", machine.getMachineCode());
        return send(machine, auth, payload);
    }

    private boolean send(Machine machine, MachineStartAuthorization auth, Map<String, Object> payload) {
        String topic = mqttPublisher.buildWasherCommandTopic(machine.getBranchId(), machine.getMachineCode());
        try {
            mqttPublisher.publish(topic, objectMapper.writeValueAsString(payload));
            return true;
        } catch (JsonProcessingException | MqttException | RuntimeException e) {
            log.error("[{}] Failed to publish {} for auth={}: {}", machine.getMachineCode(), payload.get("type"),
                    auth.getId(), e.getMessage(), e);
            return false;
        }
    }
}
