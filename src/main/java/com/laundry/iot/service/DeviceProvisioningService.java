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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Machine onboarding: pairing (admin scans the setup QR on the board's screen),
 * device authentication and per-device EMQX credential issuance. Also handles
 * unpairing / revocation.
 *
 * <p>The board generates its own 6-digit code on first boot, so there is no
 * workshop enrollment: the scan both identifies the board and proves the admin
 * is standing in front of it. The board then polls {@link #challenge} until a
 * pairing is open and proves it holds the same code in {@link #activate}.
 *
 * <p>Either order works:
 * <ul>
 *   <li>machine first: {@link #pair} with the scanned code (from "Thêm máy" or the
 *       machine's Lắp đặt tab); the board activates and gets its MQTT identity;</li>
 *   <li>device first: {@link #registerDevice} on the IoT page; the board proves the
 *       code and becomes {@code VERIFIED}. Later {@link #pair} without a code attaches
 *       it to a machine, and the board's next poll activates it.</li>
 * </ul>
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

    @Transactional(readOnly = true)
    public List<DeviceSummary> listDevices() {
        Map<UUID, String> machineCodes = machineRepository.findAll().stream()
                .collect(Collectors.toMap(Machine::getId, Machine::getMachineCode));
        return deviceRepository.findAllByOrderByEnrolledAtDesc().stream()
                .map(d -> new DeviceSummary(d.getDeviceId(), d.getStatus(), d.getMachineId(),
                        d.getMachineId() == null ? null : machineCodes.get(d.getMachineId()),
                        d.getFirmwareVersion(), d.getEnrolledAt(), d.getActivatedAt(),
                        d.getCommissionedAt(), d.getRevokedAt(), d.getVerifiedAt()))
                .toList();
    }

    // ── Device first: add the board on the IoT page ────────────────────────

    /**
     * Admin scanned (or typed) the board's code on the IoT page, before any machine
     * exists for it. Opens a machine-less verification; the board's next poll
     * proves the code and the device becomes {@code VERIFIED}.
     */
    @Transactional
    public RegisterDeviceResponse registerDevice(StartPairingRequest request, UUID adminId) {
        String setupKey = requireSetupKey(request.setupKey());
        String deviceId = normalizeDeviceId(request.deviceId());
        OffsetDateTime now = OffsetDateTime.now();
        IotDevice device = findOrCreate(deviceId);
        switch (device.getStatus()) {
            case PROVISIONED, ACTIVE -> throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Device is already bound to a machine; unpair it first");
            case PAIRING -> {
                if (openMachineSession(deviceId, now) != null) {
                    throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                            "Device is being paired to a machine right now");
                }
            }
            case UNCLAIMED, VERIFIED, REVOKED -> { }
        }
        cancelOpenSessions(sessionRepository.findByDeviceIdAndStatus(deviceId, PairingSession.Status.OPEN));
        PairingSession session = sessionRepository.save(PairingSession.builder()
                .machineId(null)
                .deviceId(deviceId)
                .status(PairingSession.Status.OPEN)
                .createdBy(adminId)
                .createdAt(now)
                .expiresAt(now.plusSeconds(properties.getPairingTtlSeconds()))
                .build());
        openForProof(device, setupKey);
        log.info("[IOT] Device-first verification {} opened for {}", session.getId(), deviceId);
        return new RegisterDeviceResponse(session.getId(), deviceId, session.getExpiresAt());
    }

    // ── Pairing with a machine ──────────────────────────────────────────────

    /** Admin scanned the board's code, or picked a VERIFIED board, for an existing machine. */
    @Transactional
    public PairingResponse startPairing(String machineCode, StartPairingRequest request, UUID adminId) {
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> MachineNotFoundException.byCode(machineCode));
        return pair(machine, request, adminId);
    }

    /**
     * Open a pairing session between {@code machine} and a board. Joins the
     * caller's transaction, so machine creation can create and pair in one step
     * and roll back both on failure.
     *
     * <p>With a code: the code cannot be checked here (only the board knows it);
     * a mistyped code surfaces as a failed proof in {@link #activate}. Without a
     * code: only a {@code VERIFIED} board, whose code the backend already holds.
     */
    @Transactional
    public PairingResponse pair(Machine machine, StartPairingRequest request, UUID adminId) {
        if (machine.getDeploymentStatus() != MachineDeploymentStatus.UNASSIGNED
                && machine.getDeploymentStatus() != MachineDeploymentStatus.PAIRING) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Machine " + machine.getMachineCode() + " is " + machine.getDeploymentStatus() + "; unpair it first");
        }
        boolean codeGiven = request.setupKey() != null && !request.setupKey().isBlank();
        String setupKey = codeGiven ? requireSetupKey(request.setupKey()) : null;
        String deviceId = normalizeDeviceId(request.deviceId());
        OffsetDateTime now = OffsetDateTime.now();
        IotDevice device = findOrCreate(deviceId);

        switch (device.getStatus()) {
            case PROVISIONED, ACTIVE -> throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Device is already bound to another machine; unpair it first");
            case PAIRING -> {
                PairingSession other = openMachineSession(deviceId, now);
                if (other != null && !other.getMachineId().equals(machine.getId())) {
                    throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                            "Device is being paired to another machine");
                }
            }
            case UNCLAIMED, VERIFIED, REVOKED -> { }
        }
        if (!codeGiven && !isVerified(device)) {
            throw new ProvisioningRejectedException(HttpStatus.BAD_REQUEST,
                    "Enter the 6-digit code shown on the board, or add the board on the IoT page first");
        }

        cancelOpenSessions(sessionRepository.findByMachineIdAndStatus(machine.getId(), PairingSession.Status.OPEN));
        cancelOpenSessions(sessionRepository.findByDeviceIdAndStatus(deviceId, PairingSession.Status.OPEN));

        PairingSession session = sessionRepository.save(PairingSession.builder()
                .machineId(machine.getId())
                .deviceId(deviceId)
                .status(PairingSession.Status.OPEN)
                .createdBy(adminId)
                .createdAt(now)
                .expiresAt(now.plusSeconds(properties.getPairingTtlSeconds()))
                .build());

        if (codeGiven) {
            openForProof(device, setupKey);
        } else {
            // VERIFIED board: reuse the code it already proved; it activates on its next poll.
            device.setStatus(IotDeviceStatus.PAIRING);
            device.setFailedPairingAttempts(0);
            device.setChallengeNonce(null);
            device.setChallengeExpiresAt(null);
            deviceRepository.save(device);
        }

        machine.setDeploymentStatus(MachineDeploymentStatus.PAIRING);
        machineRepository.save(machine);
        event(machine, DeviceEventType.DEVICE_PAIRING_STARTED,
                "Pairing " + deviceId + (codeGiven ? "" : " (verified board)") + " by admin " + adminId);
        broadcaster.broadcastMachineUpdate(machine);

        log.info("[IOT] Pairing session {} opened: {} <-> {}", session.getId(), machine.getMachineCode(), deviceId);
        return new PairingResponse(session.getId(), machine.getMachineCode(), deviceId, session.getExpiresAt());
    }

    // ── Device authentication (the unprovisioned board polls this) ──────────

    /**
     * A nonce when a pairing is open; empty (HTTP 202) when the board is VERIFIED
     * and waits to be attached to a machine, so it keeps its code and shows
     * "CHO GAN MAY"; otherwise 409 (the board may rotate its code).
     */
    @Transactional
    public Optional<ChallengeResponse> challenge(String deviceId) {
        IotDevice device = deviceRepository.findByDeviceIdForUpdate(deviceId).orElse(null);
        OffsetDateTime now = OffsetDateTime.now();
        if (device != null && device.getStatus() == IotDeviceStatus.PAIRING && openSession(deviceId, now) != null) {
            String nonce = secrets.newNonce();
            device.setChallengeNonce(nonce);
            device.setChallengeExpiresAt(now.plusSeconds(properties.getChallengeTtlSeconds()));
            deviceRepository.save(device);
            return Optional.of(new ChallengeResponse(nonce, properties.getChallengeTtlSeconds()));
        }
        if (device != null && isVerified(device)
                && (device.getStatus() == IotDeviceStatus.VERIFIED || device.getStatus() == IotDeviceStatus.PAIRING)) {
            if (device.getStatus() == IotDeviceStatus.PAIRING) {
                // The machine pairing expired before the board activated: it is still a verified board.
                device.setStatus(IotDeviceStatus.VERIFIED);
                deviceRepository.save(device);
            }
            return Optional.empty();
        }
        // Same answer for unknown and not-pairing devices: no enumeration.
        throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "No open pairing session for this device");
    }

    /**
     * Verify the ESP32's HMAC proof. For a machine pairing: bind it to the machine
     * and issue its own EMQX identity. For a device-first verification: mark it
     * VERIFIED and return empty (HTTP 202, no credential yet). The nonce is
     * single-use even when the proof fails.
     */
    @Transactional(noRollbackFor = ProvisioningRejectedException.class)
    public Optional<ActivateResponse> activate(ActivateRequest request) {
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
        String setupKey = secrets.decryptSetupKey(device.getSetupKeyEnc());
        if (!secrets.verifyProof(deviceId, setupKey, request.nonce(), request.proof())) {
            recordFailedProof(device);
            throw rejected("Activation refused: invalid proof - the code on the device is not the one"
                    + " that was scanned; scan the code on the device screen again");
        }
        PairingSession session = openSession(deviceId, now);
        if (session == null) {
            throw rejected("Activation refused: pairing session expired");
        }

        if (session.getMachineId() == null) {
            // Device first: the board is real and holds the code; keep the code until it gets a machine.
            device.setStatus(IotDeviceStatus.VERIFIED);
            device.setVerifiedAt(now);
            device.setFailedPairingAttempts(0);
            device.setFirmwareVersion(request.firmwareVersion());
            deviceRepository.save(device);
            session.setStatus(PairingSession.Status.COMPLETED);
            session.setCompletedAt(now);
            sessionRepository.save(session);
            log.info("[IOT] Device {} verified (fw {}), waiting for a machine", deviceId, request.firmwareVersion());
            return Optional.empty();
        }

        Machine machine = machineRepository.findById(session.getMachineId())
                .orElseThrow(() -> rejected("Activation refused: machine no longer exists"));
        if (machine.getDeploymentStatus() != MachineDeploymentStatus.PAIRING
                || (machine.getDeviceId() != null && !machine.getDeviceId().equals(deviceId))) {
            throw rejected("Activation refused: machine is not waiting for this device");
        }

        // Per-device EMQX identity with least-privilege ACL.
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
        device.setSetupKeyEnc(null);       // the code is single-use
        device.setFailedPairingAttempts(0);
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
        return Optional.of(new ActivateResponse(mqttHost(), mqttPort(), username, password, deviceId,
                machine.getBranchId(), machine.getMachineCode(), topicBase, machine.getType().name()));
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
            // Still PAIRING: release the device so it can be paired elsewhere. A board
            // verified on the IoT page stays VERIFIED and can be picked again.
            open.forEach(s -> deviceRepository.findByDeviceIdForUpdate(s.getDeviceId())
                    .filter(d -> d.getStatus() == IotDeviceStatus.PAIRING)
                    .ifPresent(d -> {
                        if (isVerified(d)) {
                            d.setStatus(IotDeviceStatus.VERIFIED);
                        } else {
                            d.setStatus(IotDeviceStatus.UNCLAIMED);
                            d.setSetupKeyEnc(null);
                        }
                        deviceRepository.save(d);
                    }));
        }

        String previousDevice = machine.getDeviceId();
        machine.setDeviceId(null);
        machine.setDeploymentStatus(MachineDeploymentStatus.UNASSIGNED);
        machine.setMaintenanceSince(null);   // a new device is commissioned from scratch
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
        open.stream().filter(s -> s.getMachineId() != null).forEach(s -> machineRepository.findById(s.getMachineId())
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
        device.setSetupKeyEnc(null);
        device.setVerifiedAt(null);
        device.setChallengeNonce(null);
        device.setRevokedAt(OffsetDateTime.now());
        deviceRepository.save(device);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * A wrong proof means the scanned key is not the board's (mistyped, or a
     * spoofed deviceId). After {@code maxPairingAttempts} the pairing is
     * aborted and the admin must scan the board again.
     */
    private void recordFailedProof(IotDevice device) {
        device.setFailedPairingAttempts(device.getFailedPairingAttempts() + 1);
        if (device.getFailedPairingAttempts() >= properties.getMaxPairingAttempts()) {
            List<PairingSession> open = sessionRepository.findByDeviceIdAndStatus(
                    device.getDeviceId(), PairingSession.Status.OPEN);
            cancelOpenSessions(open);
            open.stream().filter(s -> s.getMachineId() != null).forEach(s -> machineRepository.findById(s.getMachineId())
                    .filter(m -> m.getDeploymentStatus() == MachineDeploymentStatus.PAIRING && m.getDeviceId() == null)
                    .ifPresent(m -> {
                        m.setDeploymentStatus(MachineDeploymentStatus.UNASSIGNED);
                        machineRepository.save(m);
                        broadcaster.broadcastMachineUpdate(m);
                    }));
            device.setStatus(IotDeviceStatus.UNCLAIMED);
            device.setSetupKeyEnc(null);
            device.setVerifiedAt(null);
            log.warn("[IOT] Pairing of {} aborted after {} wrong proofs",
                    device.getDeviceId(), device.getFailedPairingAttempts());
        }
        deviceRepository.save(device);
    }

    private String requireSetupKey(String raw) {
        String setupKey = DeviceSecretService.normalizeSetupKey(raw);
        if (!secrets.isValidSetupKey(setupKey)) {
            throw new ProvisioningRejectedException(HttpStatus.BAD_REQUEST,
                    "Pairing code must be the 6 digits shown on the device screen");
        }
        return setupKey;
    }

    private static String normalizeDeviceId(String raw) {
        return raw.trim().toUpperCase();
    }

    /** The first scan of a board creates its row; there is no separate enrollment. */
    private IotDevice findOrCreate(String deviceId) {
        return deviceRepository.findByDeviceIdForUpdate(deviceId)
                .orElseGet(() -> IotDevice.builder().deviceId(deviceId).status(IotDeviceStatus.UNCLAIMED).build());
    }

    /** Proved its code on the IoT page and still holds it: can be attached to a machine without a new scan. */
    private static boolean isVerified(IotDevice device) {
        return device.getVerifiedAt() != null && device.getSetupKeyEnc() != null && device.getMachineId() == null;
    }

    /** Store a freshly scanned code and wait for the board's proof. */
    private void openForProof(IotDevice device, String setupKey) {
        device.setStatus(IotDeviceStatus.PAIRING);
        device.setSetupKeyEnc(secrets.encryptSetupKey(setupKey));
        device.setVerifiedAt(null);
        device.setFailedPairingAttempts(0);
        device.setChallengeNonce(null);
        device.setChallengeExpiresAt(null);
        device.setRevokedAt(null);
        deviceRepository.save(device);
    }

    private PairingSession openMachineSession(String deviceId, OffsetDateTime now) {
        PairingSession session = openSession(deviceId, now);
        return session != null && session.getMachineId() != null ? session : null;
    }

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
