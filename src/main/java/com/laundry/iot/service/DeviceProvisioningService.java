package com.laundry.iot.service;

import com.laundry.iot.config.IotProvisioningProperties;
import com.laundry.iot.dto.ProvisioningDtos.*;
import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.iot.entity.PairingSession;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.iot.repository.PairingSessionRepository;
import com.laundry.machine.entity.DeviceEvent;
import com.laundry.machine.entity.DeviceEventType;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineDeploymentStatus;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.machine.repository.DeviceEventRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineWebSocketBroadcaster;
import com.laundry.mqtt.config.MqttProperties;
import com.laundry.mqtt.service.MqttTopicBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stages 1, 3, 5 and 6 of the machine onboarding flow: enrollment, pairing,
 * device authentication and per-device EMQX credential issuance. Also
 * handles unpairing / revocation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceProvisioningService {

    private static final List<String> DEVICE_PUBLISH_CHANNELS =
            List.of("status", "heartbeat", "telemetry", "event", "auth", "command/ack");

    private final IotDeviceRepository deviceRepository;
    private final PairingSessionRepository sessionRepository;
    private final MachineRepository machineRepository;
    private final DeviceEventRepository deviceEventRepository;
    private final DeviceSecretService secrets;
    private final EmqxAdminClient emqx;
    private final MqttTopicBuilder topics;
    private final MqttProperties mqttProperties;
    private final IotProvisioningProperties properties;
    private final MachineWebSocketBroadcaster broadcaster;

    // ── Stage 1: enrollment ─────────────────────────────────────────────────

    /**
     * Register an ESP32 (or re-enroll a REVOKED/LOCKED/UNCLAIMED one). Returns
     * the claim secret and pairing code exactly once.
     */
    @Transactional
    public EnrollDeviceResponse enroll(String deviceId) {
        IotDevice device = deviceRepository.findByDeviceIdForUpdate(deviceId).orElse(null);
        if (device == null) {
            device = IotDevice.builder().deviceId(deviceId).status(IotDeviceStatus.UNCLAIMED).build();
        } else {
            if (device.getStatus() != IotDeviceStatus.UNCLAIMED
                    && device.getStatus() != IotDeviceStatus.LOCKED
                    && device.getStatus() != IotDeviceStatus.REVOKED) {
                throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                        "Device " + deviceId + " is " + device.getStatus() + "; unpair it before re-enrolling");
            }
            device.setEnrollmentVersion(device.getEnrollmentVersion() + 1);
            device.setStatus(IotDeviceStatus.UNCLAIMED);
            device.setRevokedAt(null);
        }
        String pairingCode = secrets.newPairingCode();
        device.setPairingCodeHash(secrets.sha256Hex(pairingCode));
        device.setFailedPairingAttempts(0);
        device.setChallengeNonce(null);
        device = deviceRepository.save(device);

        log.info("[IOT] Enrolled device {} (enrollment v{})", deviceId, device.getEnrollmentVersion());
        return new EnrollDeviceResponse(deviceId,
                secrets.claimSecret(deviceId, device.getEnrollmentVersion()),
                pairingCode,
                "laundryos://device?id=" + deviceId + "&code=" + pairingCode);
    }

    @Transactional(readOnly = true)
    public List<DeviceSummary> listDevices() {
        Map<UUID, String> machineCodes = machineRepository.findAll().stream()
                .collect(Collectors.toMap(Machine::getId, Machine::getMachineCode));
        return deviceRepository.findAllByOrderByEnrolledAtDesc().stream()
                .map(d -> new DeviceSummary(d.getDeviceId(), d.getStatus(), d.getMachineId(),
                        d.getMachineId() == null ? null : machineCodes.get(d.getMachineId()),
                        d.getFirmwareVersion(), d.getEnrolledAt(), d.getActivatedAt(),
                        d.getCommissionedAt(), d.getRevokedAt()))
                .toList();
    }

    // ── Stage 3: pairing ────────────────────────────────────────────────────

    /**
     * Admin scanned the device QR next to the machine. The pairing code on the
     * label is the proof that the admin is in front of the physical board.
     */
    @Transactional(noRollbackFor = ProvisioningRejectedException.class)
    public PairingResponse startPairing(String machineCode, StartPairingRequest request, UUID adminId) {
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> MachineNotFoundException.byCode(machineCode));
        if (machine.getDeploymentStatus() != MachineDeploymentStatus.UNASSIGNED
                && machine.getDeploymentStatus() != MachineDeploymentStatus.PAIRING) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Machine " + machineCode + " is " + machine.getDeploymentStatus() + "; unpair it first");
        }

        IotDevice device = deviceRepository.findByDeviceIdForUpdate(request.deviceId())
                .orElseThrow(() -> new ProvisioningRejectedException(HttpStatus.NOT_FOUND,
                        "Device " + request.deviceId() + " is not enrolled"));
        OffsetDateTime now = OffsetDateTime.now();

        switch (device.getStatus()) {
            case LOCKED, REVOKED -> throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Device is " + device.getStatus() + "; re-enroll it at the workshop");
            case PROVISIONED, ACTIVE -> throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Device is already bound to another machine");
            case PAIRING -> {
                boolean busyElsewhere = sessionRepository
                        .findFirstByDeviceIdAndStatusOrderByCreatedAtDesc(device.getDeviceId(), PairingSession.Status.OPEN)
                        .filter(s -> s.isUsable(now) && !s.getMachineId().equals(machine.getId()))
                        .isPresent();
                if (busyElsewhere) {
                    throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                            "Device is being paired to another machine");
                }
            }
            case UNCLAIMED -> { }
        }

        if (!secrets.hashMatches(device.getPairingCodeHash(), request.pairingCode().trim().toUpperCase())) {
            device.setFailedPairingAttempts(device.getFailedPairingAttempts() + 1);
            if (device.getFailedPairingAttempts() >= properties.getMaxPairingAttempts()) {
                device.setStatus(IotDeviceStatus.LOCKED);
                device.setPairingCodeHash(null);
                log.warn("[IOT] Device {} LOCKED after {} wrong pairing codes",
                        device.getDeviceId(), device.getFailedPairingAttempts());
            }
            deviceRepository.save(device);
            throw new ProvisioningRejectedException(HttpStatus.BAD_REQUEST, "Wrong pairing code");
        }

        cancelOpenSessions(sessionRepository.findByMachineIdAndStatus(machine.getId(), PairingSession.Status.OPEN));
        cancelOpenSessions(sessionRepository.findByDeviceIdAndStatus(device.getDeviceId(), PairingSession.Status.OPEN));

        PairingSession session = sessionRepository.save(PairingSession.builder()
                .machineId(machine.getId())
                .deviceId(device.getDeviceId())
                .status(PairingSession.Status.OPEN)
                .createdBy(adminId)
                .createdAt(now)
                .expiresAt(now.plusSeconds(properties.getPairingTtlSeconds()))
                .build());

        device.setStatus(IotDeviceStatus.PAIRING);
        device.setFailedPairingAttempts(0);
        deviceRepository.save(device);

        machine.setDeploymentStatus(MachineDeploymentStatus.PAIRING);
        machineRepository.save(machine);
        event(machine, DeviceEventType.DEVICE_PAIRING_STARTED,
                "Pairing " + device.getDeviceId() + " by admin " + adminId);
        broadcaster.broadcastMachineUpdate(machine);

        log.info("[IOT] Pairing session {} opened: {} ↔ {}", session.getId(), machineCode, device.getDeviceId());
        return new PairingResponse(session.getId(), machineCode, device.getDeviceId(), session.getExpiresAt());
    }

    // ── Stage 5: device authentication ──────────────────────────────────────

    @Transactional
    public ChallengeResponse challenge(String deviceId) {
        IotDevice device = deviceRepository.findByDeviceIdForUpdate(deviceId).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();
        if (device == null || device.getStatus() != IotDeviceStatus.PAIRING || openSession(deviceId, now) == null) {
            // Same answer for unknown and not-pairing devices: no enumeration.
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "No open pairing session for this device");
        }
        String nonce = secrets.newNonce();
        device.setChallengeNonce(nonce);
        device.setChallengeExpiresAt(now.plusSeconds(properties.getChallengeTtlSeconds()));
        deviceRepository.save(device);
        return new ChallengeResponse(nonce, properties.getChallengeTtlSeconds());
    }

    /**
     * Verify the ESP32's HMAC proof, bind it to the machine and issue its own
     * EMQX identity (stage 6). The nonce is single-use even when the proof fails.
     */
    @Transactional(noRollbackFor = ProvisioningRejectedException.class)
    public ActivateResponse activate(ActivateRequest request) {
        String deviceId = request.deviceId();
        IotDevice device = deviceRepository.findByDeviceIdForUpdate(deviceId)
                .orElseThrow(() -> rejected("Activation refused"));
        OffsetDateTime now = OffsetDateTime.now();

        String expectedNonce = device.getChallengeNonce();
        boolean nonceValid = expectedNonce != null && expectedNonce.equals(request.nonce())
                && device.getChallengeExpiresAt() != null && now.isBefore(device.getChallengeExpiresAt());
        device.setChallengeNonce(null);
        device.setChallengeExpiresAt(null);
        deviceRepository.save(device);

        if (device.getStatus() != IotDeviceStatus.PAIRING || !nonceValid) {
            throw rejected("Activation refused: no pairing in progress or nonce expired");
        }
        if (!secrets.verifyProof(deviceId, device.getEnrollmentVersion(), request.nonce(), request.proof())) {
            throw rejected("Activation refused: invalid proof - the claim secret on the device is not the one"
                    + " from the latest enrollment; run 'claim <secret>' with the newest secret");
        }
        PairingSession session = openSession(deviceId, now);
        if (session == null) {
            throw rejected("Activation refused: pairing session expired");
        }
        Machine machine = machineRepository.findById(session.getMachineId())
                .orElseThrow(() -> rejected("Activation refused: machine no longer exists"));
        if (machine.getDeploymentStatus() != MachineDeploymentStatus.PAIRING
                || (machine.getDeviceId() != null && !machine.getDeviceId().equals(deviceId))) {
            throw rejected("Activation refused: machine is not waiting for this device");
        }

        // Stage 6: per-device EMQX identity with least-privilege ACL.
        device.setCredentialVersion(device.getCredentialVersion() + 1);
        String username = deviceId;
        String password = secrets.mqttPassword(deviceId, device.getCredentialVersion());
        String topicBase = topics.baseTopic(machine.getBranchId(), machine.getMachineCode());
        emqx.upsertUser(username, password);
        emqx.replaceDeviceAcl(username, topicBase);

        device.setStatus(IotDeviceStatus.PROVISIONED);
        device.setMachineId(machine.getId());
        device.setMqttUsername(username);
        device.setActivatedAt(now);
        device.setPairingCodeHash(null);   // the label's code is one-time
        device.setFirmwareVersion(request.firmwareVersion());
        deviceRepository.save(device);

        session.setStatus(PairingSession.Status.COMPLETED);
        session.setCompletedAt(now);
        sessionRepository.save(session);

        machine.setDeviceId(deviceId);
        machine.setDeploymentStatus(MachineDeploymentStatus.PROVISIONED);
        machineRepository.save(machine);
        event(machine, DeviceEventType.DEVICE_PROVISIONED,
                "Device " + deviceId + " provisioned (fw " + request.firmwareVersion()
                        + ", credential v" + device.getCredentialVersion() + ")");
        broadcaster.broadcastMachineUpdate(machine);

        log.info("[IOT] Device {} activated for {}/{}", deviceId, machine.getBranchId(), machine.getMachineCode());
        return new ActivateResponse(mqttHost(), mqttPort(), username, password, deviceId,
                machine.getBranchId(), machine.getMachineCode(), topicBase);
    }

    /** MANUAL mode: the credential the admin must create in the EMQX console. */
    @Transactional(readOnly = true)
    public MqttCredentialResponse mqttCredential(String deviceId) {
        if (!emqx.isManual()) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Credentials are pushed to EMQX automatically (app.emqx.provisioning-mode=API)");
        }
        IotDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ProvisioningRejectedException(HttpStatus.NOT_FOUND, "Unknown device"));
        if (device.getMqttUsername() == null || device.getMachineId() == null
                || device.getStatus() == IotDeviceStatus.REVOKED) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "Device has no active MQTT credential");
        }
        Machine machine = machineRepository.findById(device.getMachineId())
                .orElseThrow(() -> new ProvisioningRejectedException(HttpStatus.CONFLICT, "Machine missing"));
        String base = topics.baseTopic(machine.getBranchId(), machine.getMachineCode());
        return new MqttCredentialResponse(device.getMqttUsername(),
                secrets.mqttPassword(deviceId, device.getCredentialVersion()), deviceId,
                DEVICE_PUBLISH_CHANNELS.stream().map(c -> base + "/" + c).toList(),
                List.of(base + "/command"));
    }

    // ── Unpair / revoke ─────────────────────────────────────────────────────

    /** Detach the device from the machine, delete its EMQX identity, reset the machine to UNASSIGNED. */
    @Transactional
    public void unpair(String machineCode) {
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> MachineNotFoundException.byCode(machineCode));
        if (machine.getDeploymentStatus() == MachineDeploymentStatus.UNASSIGNED) {
            return;
        }
        if (machineRepository.hasActiveBookingAllocation(machine.getId())) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Machine has active or future bookings; move them before unpairing");
        }
        List<PairingSession> open = sessionRepository.findByMachineIdAndStatus(machine.getId(), PairingSession.Status.OPEN);
        cancelOpenSessions(open);

        if (machine.getDeviceId() != null) {
            deviceRepository.findByDeviceIdForUpdate(machine.getDeviceId()).ifPresent(this::revokeDevice);
        } else {
            // Still PAIRING: release the scanned device so it can be paired elsewhere.
            open.forEach(s -> deviceRepository.findByDeviceIdForUpdate(s.getDeviceId())
                    .filter(d -> d.getStatus() == IotDeviceStatus.PAIRING)
                    .ifPresent(d -> { d.setStatus(IotDeviceStatus.UNCLAIMED); deviceRepository.save(d); }));
        }

        String previousDevice = machine.getDeviceId();
        machine.setDeviceId(null);
        machine.setDeploymentStatus(MachineDeploymentStatus.UNASSIGNED);
        machine.setOnline(false);
        machineRepository.save(machine);
        event(machine, DeviceEventType.DEVICE_REVOKED, "Unpaired device " + previousDevice);
        broadcaster.broadcastMachineUpdate(machine);
        log.warn("[IOT] Machine {} unpaired from {}", machineCode, previousDevice);
    }

    /** Revoke a device by id; unpairs its machine first when it has one. */
    @Transactional
    public void revoke(String deviceId) {
        IotDevice device = deviceRepository.findByDeviceIdForUpdate(deviceId)
                .orElseThrow(() -> new ProvisioningRejectedException(HttpStatus.NOT_FOUND, "Unknown device"));
        if (device.getMachineId() != null) {
            Machine machine = machineRepository.findById(device.getMachineId()).orElse(null);
            if (machine != null) {
                unpair(machine.getMachineCode());
                return;
            }
        }
        List<PairingSession> open = sessionRepository.findByDeviceIdAndStatus(deviceId, PairingSession.Status.OPEN);
        cancelOpenSessions(open);
        // A machine waiting on this device must not stay stuck in PAIRING.
        open.forEach(s -> machineRepository.findById(s.getMachineId())
                .filter(m -> m.getDeploymentStatus() == MachineDeploymentStatus.PAIRING && m.getDeviceId() == null)
                .ifPresent(m -> {
                    m.setDeploymentStatus(MachineDeploymentStatus.UNASSIGNED);
                    machineRepository.save(m);
                    broadcaster.broadcastMachineUpdate(m);
                }));
        revokeDevice(device);
    }

    private void revokeDevice(IotDevice device) {
        if (device.getMqttUsername() != null) {
            emqx.revoke(device.getMqttUsername(), device.getDeviceId());
        }
        device.setStatus(IotDeviceStatus.REVOKED);
        device.setMachineId(null);
        device.setMqttUsername(null);
        device.setPairingCodeHash(null);
        device.setChallengeNonce(null);
        device.setRevokedAt(OffsetDateTime.now());
        deviceRepository.save(device);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private PairingSession openSession(String deviceId, OffsetDateTime now) {
        return sessionRepository
                .findFirstByDeviceIdAndStatusOrderByCreatedAtDesc(deviceId, PairingSession.Status.OPEN)
                .filter(s -> s.isUsable(now))
                .orElse(null);
    }

    private void cancelOpenSessions(List<PairingSession> sessions) {
        for (PairingSession s : sessions) {
            s.setStatus(OffsetDateTime.now().isBefore(s.getExpiresAt())
                    ? PairingSession.Status.CANCELLED : PairingSession.Status.EXPIRED);
            sessionRepository.save(s);
        }
    }

    private String mqttHost() {
        String host = properties.getDeviceMqttHost();
        return host == null || host.isBlank() ? mqttProperties.getBrokerHost() : host;
    }

    private int mqttPort() {
        return properties.getDeviceMqttPort() > 0 ? properties.getDeviceMqttPort() : mqttProperties.getBrokerPort();
    }

    private void event(Machine machine, DeviceEventType type, String detail) {
        deviceEventRepository.save(DeviceEvent.builder()
                .machineId(machine.getId())
                .machineCode(machine.getMachineCode())
                .eventType(type)
                .detail(detail)
                .build());
    }

    private static ProvisioningRejectedException rejected(String reason) {
        log.warn("[IOT] {}", reason);
        return new ProvisioningRejectedException(HttpStatus.FORBIDDEN, reason);
    }
}
