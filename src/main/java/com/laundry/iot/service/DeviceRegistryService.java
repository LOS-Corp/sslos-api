package com.laundry.iot.service;

import com.laundry.devicelink.DeviceIdentity;
import com.laundry.devicelink.DeviceKeys;
import com.laundry.devicelink.DeviceLink;
import com.laundry.devicelink.DeviceLinkChanged;
import com.laundry.devicelink.LinkState;
import com.laundry.iot.dto.ProvisioningDtos.AvailableDevice;
import com.laundry.iot.dto.ProvisioningDtos.DeviceSummary;
import com.laundry.iot.dto.ProvisioningDtos.PairDeviceRequest;
import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.machine.entity.DeviceEvent;
import com.laundry.machine.entity.DeviceEventType;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineDeploymentStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.machine.repository.DeviceEventRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineService;
import com.laundry.machine.service.MachineWebSocketBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Board onboarding over the store broker:
 *
 * <ol>
 *   <li>a board on the store Wi-Fi says HELLO, signs the backend's nonce and gets an
 *       {@code UNCLAIMED} row ({@link #onHello});</li>
 *   <li>an admin approves it ({@link #approve}) → {@code VERIFIED};</li>
 *   <li>the owner attaches it to a machine ({@link #pair}) → {@code PROVISIONED};
 *       its first heartbeat starts the self-test, then the machine is commissioned.</li>
 * </ol>
 *
 * <p>The board signs every connection with its own key; the first key of a board that is
 * not approved yet is pinned (the admin compares its fingerprint with the board's screen before
 * approving), and any other key later is refused until an admin trusts it ({@link #trustPendingKey}).
 * Every change pushes the new {@link LinkState} to the board.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceRegistryService {

    private final IotDeviceRepository deviceRepository;
    private final MachineRepository machineRepository;
    private final DeviceEventRepository deviceEventRepository;
    private final MachineWebSocketBroadcaster broadcaster;
    private final MachineService machineService;
    private final DeviceLink deviceLink;
    private final ApplicationEventPublisher events;

    /** Customer app base URL; the board shows {@code <base>/machines/<code>} as a QR code once ACTIVE. */
    @Value("${app.machine.qr-base-url:}")
    private String qrBaseUrl = "";

    @Transactional(readOnly = true)
    public List<DeviceSummary> listDevices() {
        Map<UUID, String> machineCodes = machineRepository.findAll().stream()
                .collect(Collectors.toMap(Machine::getId, Machine::getMachineCode));
        return deviceRepository.findAllByOrderByEnrolledAtDesc().stream()
                .map(d -> toSummary(d, d.getMachineId() == null ? null : machineCodes.get(d.getMachineId())))
                .toList();
    }

    /** Approved boards with no machine, optionally only those running {@code type} firmware. */
    @Transactional(readOnly = true)
    public List<AvailableDevice> availableDevices(MachineType type) {
        return deviceRepository.findAllByOrderByEnrolledAtDesc().stream()
                .filter(d -> d.getStatus() == IotDeviceStatus.VERIFIED && d.getMachineId() == null)
                .filter(d -> type == null || d.getDeviceType() == null || d.getDeviceType() == type)
                .map(d -> new AvailableDevice(d.getDeviceId(), d.getDeviceType(), d.getFirmwareVersion(),
                        deviceLink.isConnected(d.getDeviceId()), d.getVerifiedAt()))
                .toList();
    }

    // ── Link callbacks (from the device link hub) ──────────────────────────

    /**
     * A board said HELLO and signed this session's nonce with {@code identity.publicKey()}.
     * Accepts it when that key is the pinned one, pins it when the board has never been approved,
     * and otherwise refuses it ({@code AUTH_FAILED}) and keeps the key as pending for an admin.
     */
    @Transactional
    public LinkState onHello(String deviceId, String firmwareVersion, String deviceType, DeviceIdentity identity) {
        String key = DeviceKeys.encode(identity.publicKey());
        String fingerprint = DeviceKeys.fingerprint(identity.publicKey());
        OffsetDateTime now = OffsetDateTime.now();
        IotDevice device = deviceRepository.findByDeviceIdForUpdate(deviceId)
                .orElseGet(() -> {
                    log.info("[IOT] New board {} joined (key {}); waiting for admin approval",
                            deviceId, DeviceKeys.shortFingerprint(fingerprint));
                    return IotDevice.builder().deviceId(deviceId).status(IotDeviceStatus.UNCLAIMED).build();
                });
        String refusal = keyRefusal(device, fingerprint);
        if (refusal != null) {
            log.warn("[IOT] {} refused: {} (key {})", deviceId, refusal, DeviceKeys.shortFingerprint(fingerprint));
            device.setPendingPublicKey(key);
            device.setPendingKeyFingerprint(fingerprint);
            device.setPendingKeySeenAt(now);
            device.setAuthFailures(device.getAuthFailures() + 1);
            device.setLastAuthFailureAt(now);
            deviceRepository.save(device);
            return LinkState.of(LinkState.State.AUTH_FAILED, deviceId);
        }
        if (device.getKeyFingerprint() == null) {
            device.setPublicKey(key);
            device.setKeyFingerprint(fingerprint);
            log.info("[IOT] Pinned key {} for {}", DeviceKeys.shortFingerprint(fingerprint), deviceId);
        }
        device.setPendingPublicKey(null);
        device.setPendingKeyFingerprint(null);
        device.setPendingKeySeenAt(null);
        device.setFirmwareVersion(firmwareVersion);
        MachineType type = parseType(deviceType);
        if (type != null) device.setDeviceType(type);
        device.setLastSeenAt(now);
        device.setLastIp(cleanIp(identity.ip()));
        device.setLastRssi(identity.rssi() == null || identity.rssi() < -127 || identity.rssi() > 0 ? null : identity.rssi());
        deviceRepository.save(device);
        return linkStateOf(device);
    }

    /** Why {@code fingerprint} may not speak for {@code device}, or {@code null} when it may. */
    private String keyRefusal(IotDevice device, String fingerprint) {
        if (device.getKeyFingerprint() != null) {
            return device.getKeyFingerprint().equals(fingerprint) ? null : "the key differs from the pinned one";
        }
        String owner = deviceRepository.findByKeyFingerprint(fingerprint)
                .map(IotDevice::getDeviceId).filter(id -> !id.equals(device.getDeviceId())).orElse(null);
        if (owner != null) return "the key already belongs to " + owner;
        // Trust on first use only before approval: a board approved without a key must be confirmed again.
        return switch (device.getStatus()) {
            case UNCLAIMED, REVOKED -> null;
            case VERIFIED, PROVISIONED, ACTIVE -> "the board was approved before it had a key";
        };
    }

    /**
     * Admin compared the pending key's fingerprint with the board's screen: pin it. Used after the
     * board's flash was erased (new key) or for a board approved before it had a key.
     */
    @Transactional
    public DeviceSummary trustPendingKey(String deviceId, UUID adminId) {
        IotDevice device = requireDevice(deviceId);
        if (device.getPendingPublicKey() == null || device.getPendingKeyFingerprint() == null) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "Device " + deviceId + " has no key waiting to be trusted");
        }
        String fingerprint = device.getPendingKeyFingerprint();
        deviceRepository.findByKeyFingerprint(fingerprint)
                .filter(other -> !other.getDeviceId().equals(device.getDeviceId()))
                .ifPresent(other -> {
                    throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                            "This key already belongs to " + other.getDeviceId() + "; it cannot identify two boards");
                });
        device.setPublicKey(device.getPendingPublicKey());
        device.setKeyFingerprint(fingerprint);
        device.setPendingPublicKey(null);
        device.setPendingKeyFingerprint(null);
        device.setPendingKeySeenAt(null);
        device.setAuthFailures(0);
        deviceRepository.save(device);
        events.publishEvent(new DeviceLinkChanged(device.getDeviceId()));
        log.warn("[IOT] Admin {} trusted key {} for {}", adminId, DeviceKeys.shortFingerprint(fingerprint), deviceId);
        return summary(device);
    }

    @Transactional(readOnly = true)
    public LinkState linkState(String deviceId) {
        return deviceRepository.findByDeviceId(deviceId)
                .map(this::linkStateOf)
                .orElse(LinkState.of(LinkState.State.HELLO_REQUIRED, deviceId));
    }

    /** The board was unplugged: its machine is offline right away instead of after the heartbeat timeout. */
    @Transactional
    public void onDisconnected(String deviceId) {
        deviceRepository.findByDeviceId(deviceId)
                .filter(d -> d.getMachineId() != null)
                .flatMap(d -> machineRepository.findById(d.getMachineId()))
                .ifPresent(m -> machineService.markOfflineFromDevice(m.getMachineCode(),
                        "OFFLINE", null));
    }

    // ── Admin: approve / revoke ────────────────────────────────────────────

    /** Admin confirms the board is genuine. A revoked board can be approved again. */
    @Transactional
    public DeviceSummary approve(String deviceId, UUID adminId) {
        IotDevice device = requireDevice(deviceId);
        switch (device.getStatus()) {
            case VERIFIED -> { return summary(device); }
            case PROVISIONED, ACTIVE -> throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Device is already attached to a machine");
            case UNCLAIMED, REVOKED -> { }
        }
        device.setStatus(IotDeviceStatus.VERIFIED);
        device.setVerifiedAt(OffsetDateTime.now());
        device.setVerifiedBy(adminId);
        device.setRevokedAt(null);
        deviceRepository.save(device);
        events.publishEvent(new DeviceLinkChanged(deviceId));
        log.info("[IOT] Device {} approved by {}", deviceId, adminId);
        return summary(device);
    }

    /** Revoke a device by id; unpairs its machine first when it has one. Also rejects an unknown board. */
    @Transactional
    public void revoke(String deviceId) {
        IotDevice device = requireDevice(deviceId);
        if (device.getMachineId() != null) {
            Machine machine = machineRepository.findById(device.getMachineId()).orElse(null);
            if (machine != null) {
                unpair(machine.getMachineCode());
                return;
            }
        }
        revokeDevice(device);
    }

    // ── Owner: attach / detach ─────────────────────────────────────────────

    @Transactional
    public void startPairing(String machineCode, PairDeviceRequest request, UUID actorId) {
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> MachineNotFoundException.byCode(machineCode));
        pair(machine, request, actorId);
    }

    /**
     * Attach an approved board to {@code machine}. Joins the caller's transaction,
     * so machine creation can create and pair in one step and roll back both on failure.
     */
    @Transactional
    public void pair(Machine machine, PairDeviceRequest request, UUID actorId) {
        if (machine.getDeploymentStatus() != MachineDeploymentStatus.UNASSIGNED) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Machine " + machine.getMachineCode() + " is " + machine.getDeploymentStatus() + "; unpair it first");
        }
        String deviceId = request.deviceId().trim().toUpperCase(Locale.ROOT);
        IotDevice device = requireDevice(deviceId);
        if (device.getStatus() != IotDeviceStatus.VERIFIED || device.getMachineId() != null) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, switch (device.getStatus()) {
                case UNCLAIMED -> "Device " + deviceId + " has not been approved by an admin yet";
                case REVOKED -> "Device " + deviceId + " is revoked; an admin must approve it again";
                default -> "Device " + deviceId + " is already attached to another machine";
            });
        }
        if (device.getDeviceType() != null && device.getDeviceType() != machine.getType()) {
            throw new ProvisioningRejectedException(HttpStatus.BAD_REQUEST,
                    "Device " + deviceId + " runs " + device.getDeviceType() + " firmware but "
                            + machine.getMachineCode() + " is a " + machine.getType());
        }

        OffsetDateTime now = OffsetDateTime.now();
        device.setStatus(IotDeviceStatus.PROVISIONED);
        device.setMachineId(machine.getId());
        device.setActivatedAt(now);
        device.setLastSelfTestCommandId(null);
        device.setLastSelfTestAt(null);
        device.setLastSelfTestPassed(null);
        device.setLastSelfTestDetail(null);
        deviceRepository.save(device);

        machine.setDeviceId(deviceId);
        machine.setDeploymentStatus(MachineDeploymentStatus.PROVISIONED);
        machine.setOnline(false);   // its next heartbeat on the bound link starts the self-test
        machineRepository.save(machine);
        event(machine, DeviceEventType.DEVICE_PROVISIONED, "Device " + deviceId + " attached"
                + (actorId == null ? "" : " by " + actorId));
        broadcaster.broadcastMachineUpdate(machine);
        events.publishEvent(new DeviceLinkChanged(deviceId));
        log.info("[IOT] Device {} attached to {}", deviceId, machine.getMachineCode());
    }

    /** Detach and revoke the device; the machine returns to UNASSIGNED. */
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
        String previousDevice = machine.getDeviceId();
        if (previousDevice != null) {
            deviceRepository.findByDeviceIdForUpdate(previousDevice).ifPresent(this::revokeDevice);
        }
        machine.setDeviceId(null);
        machine.setDeploymentStatus(MachineDeploymentStatus.UNASSIGNED);
        machine.setMaintenanceSince(null);   // a new device is commissioned from scratch
        machine.setOnline(false);
        machineRepository.save(machine);
        event(machine, DeviceEventType.DEVICE_REVOKED, "Unpaired device " + previousDevice);
        broadcaster.broadcastMachineUpdate(machine);
        log.warn("[IOT] Machine {} unpaired from {}", machineCode, previousDevice);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void revokeDevice(IotDevice device) {
        device.setStatus(IotDeviceStatus.REVOKED);
        device.setMachineId(null);
        device.setVerifiedAt(null);
        device.setVerifiedBy(null);
        device.setRevokedAt(OffsetDateTime.now());
        deviceRepository.save(device);
        events.publishEvent(new DeviceLinkChanged(device.getDeviceId()));
    }

    private LinkState linkStateOf(IotDevice device) {
        return switch (device.getStatus()) {
            case UNCLAIMED -> LinkState.of(LinkState.State.PENDING, device.getDeviceId());
            case VERIFIED -> LinkState.of(LinkState.State.VERIFIED, device.getDeviceId());
            case REVOKED -> LinkState.of(LinkState.State.REVOKED, device.getDeviceId());
            case PROVISIONED, ACTIVE -> device.getMachineId() == null
                    ? LinkState.of(LinkState.State.VERIFIED, device.getDeviceId())
                    : machineRepository.findById(device.getMachineId())
                            .map(m -> LinkState.bound(device.getDeviceId(), m.getMachineCode(),
                                    m.getType().name(), qrUrlOf(m)))
                            .orElse(LinkState.of(LinkState.State.VERIFIED, device.getDeviceId()));
        };
    }

    /** Same URL as the printed label (GET /api/machines/{code}/qr); null until the machine may be sold. */
    private String qrUrlOf(Machine machine) {
        if (!machine.isCommissioned() || qrBaseUrl == null || qrBaseUrl.isBlank()) return null;
        return qrBaseUrl.replaceAll("/+$", "") + "/machines/" + machine.getMachineCode();
    }

    private IotDevice requireDevice(String deviceId) {
        return deviceRepository.findByDeviceIdForUpdate(deviceId.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new ProvisioningRejectedException(HttpStatus.NOT_FOUND,
                        "Unknown device " + deviceId + "; connect it to the store Wi-Fi first"));
    }

    private DeviceSummary summary(IotDevice d) {
        String machineCode = d.getMachineId() == null ? null
                : machineRepository.findById(d.getMachineId()).map(Machine::getMachineCode).orElse(null);
        return toSummary(d, machineCode);
    }

    private DeviceSummary toSummary(IotDevice d, String machineCode) {
        boolean connected = deviceLink.isConnected(d.getDeviceId());
        return new DeviceSummary(d.getDeviceId(), d.getStatus(), d.getDeviceType(), d.getMachineId(), machineCode,
                d.getFirmwareVersion(), connected,
                d.getEnrolledAt(), d.getLastSeenAt(), d.getVerifiedAt(), d.getActivatedAt(),
                d.getCommissionedAt(), d.getRevokedAt(),
                d.getLastIp(), d.getLastRssi(),
                DeviceKeys.shortFingerprint(d.getKeyFingerprint()), DeviceKeys.shortFingerprint(d.getPendingKeyFingerprint()),
                d.getAuthFailures(), d.getLastAuthFailureAt());
    }

    /** Only a plain IPv4/IPv6 literal from the board is stored. */
    private static String cleanIp(String raw) {
        if (raw == null) return null;
        String ip = raw.trim();
        return ip.length() <= 45 && ip.matches("[0-9A-Fa-f:.]+") ? ip : null;
    }

    private static MachineType parseType(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MachineType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void event(Machine machine, DeviceEventType type, String detail) {
        deviceEventRepository.save(DeviceEvent.builder()
                .machineId(machine.getId())
                .machineCode(machine.getMachineCode())
                .eventType(type)
                .detail(detail)
                .build());
    }
}
