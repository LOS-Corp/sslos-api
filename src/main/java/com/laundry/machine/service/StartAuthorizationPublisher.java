package com.laundry.machine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStartAuthorization;
import com.laundry.machine.entity.PriceProfile;
import com.laundry.machine.repository.PriceProfileRepository;
import com.laundry.devicelink.DeviceLink;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Messages of the START handshake sent to the controller (AUTHORIZE_START, START_APPROVAL, REVOKE_START).
 * A failed send is logged, never thrown: the authorization then expires or times out on its own
 * (BR-08, BR-31).
 */
@Slf4j
@Component
public class StartAuthorizationPublisher {

    private final DeviceLink deviceLink;
    private final PriceProfileRepository priceProfiles;
    private final ObjectMapper objectMapper;
    private final int authorizationTtlSeconds;

    public StartAuthorizationPublisher(DeviceLink deviceLink,
                                       PriceProfileRepository priceProfiles,
                                       ObjectMapper objectMapper,
                                       @Value("${app.machine.start-authorization.ttl-seconds:120}") int ttlSeconds) {
        this.deviceLink = deviceLink;
        this.priceProfiles = priceProfiles;
        this.objectMapper = objectMapper;
        this.authorizationTtlSeconds = ttlSeconds;
    }

    /**
     * AUTHORIZE_START. V69: the machine runs its own firmware program; the backend only sends {@code cycleSeconds},
     * the cycle of the machine's price profile. A dryer dries exactly that long (the paid package); a washer and a
     * retrofit board use it as the expected length. Without a profile the firmware uses its own default.
     */
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
        cycleSeconds(machine).ifPresent(seconds -> payload.put("cycleSeconds", seconds));
        // V67: pulses that open a third-party machine (retrofit board); LaundryOS boards ignore it.
        if (machine.getVendPulses() != null) payload.put("vendPulses", machine.getVendPulses());
        send(machine, auth, payload);
    }

    private java.util.Optional<Integer> cycleSeconds(Machine machine) {
        if (machine.getPriceProfileId() == null) return java.util.Optional.empty();
        try {
            return priceProfiles.findById(machine.getPriceProfileId())
                    .map(PriceProfile::getCycleMinutes)
                    .map(minutes -> minutes * 60);
        } catch (RuntimeException e) {
            log.warn("[{}] Price profile lookup failed, the board runs its default cycle: {}",
                    machine.getMachineCode(), e.getMessage());
            return java.util.Optional.empty();
        }
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

    /**
     * LOAD_VERDICT: result of weighing the laundry before START (V61). {@code accepted} unlocks the next
     * START press; otherwise the washer shows why and keeps START locked until the customer takes laundry
     * out (and weighs again) or pays for the extra kilograms in the app. {@code expiresInSeconds} replaces
     * the device's authorization timer, since paying takes longer than the usual unlock window.
     */
    public void loadVerdict(Machine machine, MachineStartAuthorization auth, boolean accepted, String reason,
                            Integer extraKg, java.math.BigDecimal paidKg, long expiresInSeconds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "LOAD_VERDICT");
        payload.put("commandId", "LOAD-" + auth.getId());
        payload.put("authorizationId", auth.getId().toString());
        payload.put("machineId", machine.getMachineCode());
        payload.put("accepted", accepted);
        payload.put("reason", reason);
        if (auth.getMeasuredLoadKg() != null) payload.put("measuredKg", auth.getMeasuredLoadKg());
        if (paidKg != null) payload.put("paidKg", paidKg);
        if (machine.getCapacityKg() != null) payload.put("capacityKg", machine.getCapacityKg());
        if (extraKg != null) payload.put("extraKg", extraKg);
        payload.put("expiresInSeconds", Math.max(1, expiresInSeconds));
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
        try {
            deviceLink.send(machine, objectMapper.writeValueAsString(payload));
            return true;
        } catch (JsonProcessingException | RuntimeException e) {
            log.error("[{}] Failed to send {} for auth={}: {}", machine.getMachineCode(), payload.get("type"),
                    auth.getId(), e.getMessage());
            return false;
        }
    }
}
