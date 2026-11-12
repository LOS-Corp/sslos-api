package com.laundry.iot.service;

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
 * Board onboarding over the USB serial link:
 *
 * <ol>
 *   <li>a board plugged into the store server says HELLO and gets a
 *       {@code UNCLAIMED} row ({@link #onHello});</li>
 *   <li>an admin approves it ({@link #approve}) → {@code VERIFIED};</li>
 *   <li>the owner attaches it to a machine ({@link #pair}) → {@code PROVISIONED};
 *       its first heartbeat starts the self-test, then the machine is commissioned.</li>
 * </ol>
 *
 * <p>There is no secret to exchange: only a board physically plugged into the
 * server can talk to the backend, and an admin decides which boards are real.
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
                .map(d -> new DeviceSummary(d.getDeviceId(), d.getStatus(), d.getDeviceType(), d.getMachineId(),
                        d.getMachineId() == null ? null : machineCodes.get(d.getMachineId()),
                        d.getFirmwareVersion(), deviceLink.isConnected(d.getDeviceId()),
                        deviceLink.portOf(d.getDeviceId()), d.getEnrolledAt(), d.getLastSeenAt(),
                        d.getVerifiedAt(), d.getActivatedAt(), d.getCommissionedAt(), d.getRevokedAt()))
                .toList();
    }

    /** Approved boards with no machine, optionally only those running {@code type} firmware. */
    @Transactional(readOnly = true)
    public List<AvailableDevice> availableDevices(MachineType type) {
        return deviceRepository.findAllByOrderByEnrolledAtDesc().stream()
                .filter(d -> d.getStatus() == IotDeviceStatus.VERIFIED && d.getMachineId() == null)
                .filter(d -> type == null || d.getDeviceType() == null || d.getDeviceType() == type)
                .map(d -> new AvailableDevice(d.getDeviceId(), d.getDeviceType(), d.getFirmwareVersion(),
                        deviceLink.isConnected(d.getDeviceId()), deviceLink.portOf(d.getDeviceId()), d.getVerifiedAt()))
                .toList();
    }

    // ── Link callbacks (from the serial link) ──────────────────────────────

    /** A board said HELLO: record it (first time = UNCLAIMED) and tell it where it stands. */
    @Transactional
    public LinkState onHello(String deviceId, String firmwareVersion, String deviceType) {
        IotDevice device = deviceRepository.findByDeviceIdForUpdate(deviceId)
                .orElseGet(() -> {
                    log.info("[IOT] New board {} seen on USB; waiting for admin approval", deviceId);
                    return IotDevice.builder().deviceId(deviceId).status(IotDeviceStatus.UNCLAIMED).build();
                });
        device.setFirmwareVersion(firmwareVersion);
        MachineType type = parseType(deviceType);
        if (type != null) device.setDeviceType(type);
        device.setLastSeenAt(OffsetDateTime.now());
        deviceRepository.save(device);
        return linkStateOf(device);
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
                .ifPresent(m -> machineService.markOfflineFromDevice(m.getBranchId(), m.getMachineCode(),
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
        log.info("[IOT] Device {} attached to {}/{}", deviceId, machine.getBranchId(), machine.getMachineCode());
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
                            .map(m -> LinkState.bound(device.getDeviceId(), m.getBranchId(), m.getMachineCode(),
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
                        "Unknown device " + deviceId + "; plug it into the store server first"));
    }

    private DeviceSummary summary(IotDevice d) {
        String machineCode = d.getMachineId() == null ? null
                : machineRepository.findById(d.getMachineId()).map(Machine::getMachineCode).orElse(null);
        return new DeviceSummary(d.getDeviceId(), d.getStatus(), d.getDeviceType(), d.getMachineId(), machineCode,
                d.getFirmwareVersion(), deviceLink.isConnected(d.getDeviceId()), deviceLink.portOf(d.getDeviceId()),
                d.getEnrolledAt(), d.getLastSeenAt(), d.getVerifiedAt(), d.getActivatedAt(),
                d.getCommissionedAt(), d.getRevokedAt());
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
