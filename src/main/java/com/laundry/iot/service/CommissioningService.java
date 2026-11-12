package com.laundry.iot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.iot.config.IotProvisioningProperties;
import com.laundry.iot.dto.ProvisioningDtos.ProvisioningStatus;
import com.laundry.iot.dto.ProvisioningDtos.SelfTestResponse;
import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.machine.entity.*;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.machine.repository.DeviceCommandRepository;
import com.laundry.machine.repository.DeviceEventRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import com.laundry.machine.service.MachineWebSocketBroadcaster;
import com.laundry.devicelink.DeviceLink;
import com.laundry.devicelink.DeviceLinkChanged;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stages 7 and 8: remote self-test over USB and commissioning.
 *
 * <p>The self-test is a {@code SELF_TEST} command on {@code .../command}. The
 * device acks on {@code .../command/ack}, checks door, START button (the
 * technician presses it), motor (short no-load pulse each way), load cell and
 * indicators, then reports {@code SELF_TEST_RESULT} on {@code .../event}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CommissioningService {

    private static final Set<MachineDeploymentStatus> TESTABLE = EnumSet.of(
            MachineDeploymentStatus.PROVISIONED, MachineDeploymentStatus.TESTING,
            MachineDeploymentStatus.TEST_FAILED, MachineDeploymentStatus.TEST_PASSED,
            MachineDeploymentStatus.MAINTENANCE);

    /** Runtime states of a live customer session; maintenance must wait for them to end. */
    private static final Set<MachineStatus> SESSION_STATUSES = EnumSet.of(
            MachineStatus.RESERVED, MachineStatus.WAITING_START,
            MachineStatus.RUNNING, MachineStatus.PAUSED);

    private final MachineRepository machineRepository;
    private final MachineStartAuthorizationRepository startAuthorizationRepository;
    private final IotDeviceRepository deviceRepository;
    private final DeviceCommandRepository commandRepository;
    private final DeviceEventRepository eventRepository;
    private final DeviceLink deviceLink;
    private final ObjectMapper objectMapper;
    private final MachineWebSocketBroadcaster broadcaster;
    private final IotProvisioningProperties properties;
    private final ApplicationEventPublisher events;

    @Value("${app.machine.heartbeat-timeout-seconds:30}")
    private int heartbeatTimeoutSeconds;

    @Value("${app.machine.command-timeout-seconds:15}")
    private int commandTimeoutSeconds;

    @Transactional(readOnly = true)
    public ProvisioningStatus status(String machineCode) {
        Machine machine = requireMachine(machineCode);
        IotDevice device = machine.getDeviceId() == null ? null
                : deviceRepository.findByDeviceId(machine.getDeviceId()).orElse(null);
        return new ProvisioningStatus(machine.getMachineCode(), machine.getDeploymentStatus(),
                machine.getDeviceId(), device == null ? null : device.getStatus(),
                deviceLink.isConnected(machine.getDeviceId()), deviceLink.portOf(machine.getDeviceId()),
                machine.isOnline(),
                machine.getLastHeartbeatAt(), device == null ? null : device.getFirmwareVersion(),
                device == null ? null : device.getLastSelfTestAt(),
                device == null ? null : device.getLastSelfTestPassed(),
                device == null ? null : device.getLastSelfTestDetail(),
                machine.getMaintenanceSince());
    }

    // ── Stage 7: self-test ──────────────────────────────────────────────────

    @Transactional
    public SelfTestResponse startSelfTest(String machineCode, UUID adminId) {
        Machine machine = requireMachine(machineCode);
        if (!TESTABLE.contains(machine.getDeploymentStatus())) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Machine is " + machine.getDeploymentStatus() + "; it must be provisioned before testing");
        }
        requireFreshHeartbeat(machine);
        if (machine.getStatus() == MachineStatus.RUNNING) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "Machine is running a cycle");
        }
        IotDevice device = requireDevice(machine);

        String commandId = "SELFTEST-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        DeviceCommand command = commandRepository.save(DeviceCommand.builder()
                .commandId(commandId)
                .machineId(machine.getId())
                .machineCode(machine.getMachineCode())
                .command("SELF_TEST")
                .issuedByUserId(adminId)
                .status(DeviceCommandStatus.PENDING)
                .timeoutAt(now.plusSeconds(commandTimeoutSeconds))
                .build());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "SELF_TEST");
        payload.put("command", "SELF_TEST");
        payload.put("commandId", commandId);
        payload.put("branchId", machine.getBranchId().toString());
        payload.put("machineId", machine.getMachineCode());
        payload.put("buttonTimeoutSeconds", properties.getSelfTestButtonTimeoutSeconds());
        try {
            deviceLink.send(machine, objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            command.setStatus(DeviceCommandStatus.FAILED);
            command.setAckReason("Send failed: " + e.getMessage());
            commandRepository.save(command);
            throw new IllegalStateException("Failed to send SELF_TEST to device: " + e.getMessage(), e);
        }
        command.setStatus(DeviceCommandStatus.SENT);
        command.setSentAt(OffsetDateTime.now());
        commandRepository.save(command);

        device.setLastSelfTestCommandId(commandId);
        device.setLastSelfTestAt(now);
        device.setLastSelfTestPassed(null);
        device.setLastSelfTestDetail(null);
        deviceRepository.save(device);

        machine.setDeploymentStatus(MachineDeploymentStatus.TESTING);
        machineRepository.save(machine);
        broadcaster.broadcastMachineUpdate(machine);
        log.info("[IOT] SELF_TEST {} sent to {}", commandId, machineCode);
        return new SelfTestResponse(commandId, properties.getSelfTestButtonTimeoutSeconds());
    }

    /**
     * Called from the device {@code event} handler for {@code SELF_TEST_RESULT}.
     * Ignores results that do not match the outstanding command.
     */
    @Transactional
    public void handleSelfTestResult(UUID branchId, String machineCode, String commandId,
                                     boolean passed, String detailJson) {
        Machine machine = machineRepository.findByBranchIdAndMachineCode(branchId, machineCode).orElse(null);
        if (machine == null || machine.getDeviceId() == null) {
            log.warn("[IOT] SELF_TEST_RESULT for unknown/unpaired machine {}/{}", branchId, machineCode);
            return;
        }
        if (machine.getDeploymentStatus() != MachineDeploymentStatus.TESTING) {
            log.warn("[IOT] SELF_TEST_RESULT ignored: {} is {}", machineCode, machine.getDeploymentStatus());
            return;
        }
        IotDevice device = deviceRepository.findByDeviceId(machine.getDeviceId()).orElse(null);
        if (device == null || commandId == null || !commandId.equals(device.getLastSelfTestCommandId())) {
            log.warn("[IOT] SELF_TEST_RESULT ignored: stale commandId {} for {}", commandId, machineCode);
            return;
        }
        String detail = detailJson != null && detailJson.length() > 4000 ? detailJson.substring(0, 4000) : detailJson;
        device.setLastSelfTestPassed(passed);
        device.setLastSelfTestDetail(detail);
        deviceRepository.save(device);

        // A failed post-repair test sends the machine back to the repair bench.
        MachineDeploymentStatus failedStatus = machine.isUnderMaintenance()
                ? MachineDeploymentStatus.MAINTENANCE : MachineDeploymentStatus.TEST_FAILED;
        machine.setDeploymentStatus(passed ? MachineDeploymentStatus.TEST_PASSED : failedStatus);
        machineRepository.save(machine);
        eventRepository.save(DeviceEvent.builder()
                .machineId(machine.getId())
                .machineCode(machine.getMachineCode())
                .eventType(passed ? DeviceEventType.SELF_TEST_PASSED : DeviceEventType.SELF_TEST_FAILED)
                .detail(detail)
                .rawPayload(detailJson != null && detailJson.length() > 2000 ? detailJson.substring(0, 2000) : detailJson)
                .build());
        broadcaster.broadcastMachineUpdate(machine);
        log.info("[IOT] SELF_TEST {} on {}: {}", commandId, machineCode, passed ? "PASSED" : "FAILED");
    }

    // ── Stage 8: commissioning ──────────────────────────────────────────────

    @Transactional
    public ProvisioningStatus commission(String machineCode, UUID adminId) {
        Machine machine = requireMachine(machineCode);
        if (machine.getDeploymentStatus() != MachineDeploymentStatus.TEST_PASSED) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Machine is " + machine.getDeploymentStatus() + "; a passing self-test is required");
        }
        requireFreshHeartbeat(machine);
        IotDevice device = requireDevice(machine);
        if (!Boolean.TRUE.equals(device.getLastSelfTestPassed())) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "Last self-test did not pass");
        }

        if (machine.isUnderMaintenance()) {
            // Return from repair: the device keeps its original commissioning record.
            OffsetDateTime since = machine.getMaintenanceSince();
            machine.setMaintenanceSince(null);
            machine.setDeploymentStatus(MachineDeploymentStatus.ACTIVE);
            machineRepository.save(machine);
            event(machine, DeviceEventType.MAINTENANCE_COMPLETED,
                    "Maintenance since " + since + " completed by " + adminId
                            + " after a passing self-test");
            broadcaster.broadcastMachineUpdate(machine);
            events.publishEvent(new DeviceLinkChanged(device.getDeviceId()));  // the board shows the QR again
            log.info("[IOT] Machine {} back from maintenance, approved by {}", machineCode, adminId);
            return status(machineCode);
        }

        OffsetDateTime now = OffsetDateTime.now();
        device.setStatus(IotDeviceStatus.ACTIVE);
        device.setCommissionedAt(now);
        device.setCommissionedBy(adminId);
        deviceRepository.save(device);

        machine.setDeploymentStatus(MachineDeploymentStatus.ACTIVE);
        machineRepository.save(machine);
        event(machine, DeviceEventType.MACHINE_COMMISSIONED,
                "Commissioned by " + adminId + " with device " + device.getDeviceId()
                        + " fw " + device.getFirmwareVersion());
        broadcaster.broadcastMachineUpdate(machine);
        events.publishEvent(new DeviceLinkChanged(device.getDeviceId()));  // the board starts showing its QR
        log.info("[IOT] Machine {} commissioned by {}", machineCode, adminId);
        return status(machineCode);
    }

    // ── Maintenance: ACTIVE → MAINTENANCE → (self-test) → ACTIVE ────────────

    /**
     * Take an ACTIVE machine out of booking for a hardware repair. Refused while
     * a customer session, a start authorization or a live/future booking still
     * uses the machine. The row lock serialises this with booking creation,
     * which re-checks {@code deployment_status} under {@code FOR UPDATE}.
     */
    @Transactional
    public ProvisioningStatus startMaintenance(String machineCode, UUID adminId, String reason) {
        Machine machine = machineRepository.findByIdForReservation(requireMachine(machineCode).getId())
                .orElseThrow(() -> MachineNotFoundException.byCode(machineCode));
        if (machine.getDeploymentStatus() != MachineDeploymentStatus.ACTIVE) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Machine is " + machine.getDeploymentStatus() + "; only an ACTIVE machine can enter maintenance");
        }
        if (SESSION_STATUSES.contains(machine.getStatus())) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Machine is " + machine.getStatus() + "; wait for the customer session to end");
        }
        if (startAuthorizationRepository.findActiveByMachine(machine.getId()).isPresent()) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Machine has a pending start authorization");
        }
        if (machineRepository.hasActiveBookingAllocation(machine.getId())) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Machine has active or future bookings; move them before maintenance");
        }

        machine.setMaintenanceSince(OffsetDateTime.now());
        machine.setDeploymentStatus(MachineDeploymentStatus.MAINTENANCE);
        machineRepository.save(machine);
        String note = reason == null || reason.isBlank() ? "" : ": " + reason.strip();
        event(machine, DeviceEventType.MAINTENANCE_STARTED, "Maintenance started by " + adminId + note);
        broadcaster.broadcastMachineUpdate(machine);
        if (machine.getDeviceId() != null) events.publishEvent(new DeviceLinkChanged(machine.getDeviceId()));  // hide the QR
        log.info("[IOT] Machine {} entered maintenance by {}", machineCode, adminId);
        return status(machineCode);
    }

    private void event(Machine machine, DeviceEventType type, String detail) {
        eventRepository.save(DeviceEvent.builder()
                .machineId(machine.getId())
                .machineCode(machine.getMachineCode())
                .eventType(type)
                .detail(detail)
                .build());
    }

    private Machine requireMachine(String machineCode) {
        return machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> MachineNotFoundException.byCode(machineCode));
    }

    private IotDevice requireDevice(Machine machine) {
        if (machine.getDeviceId() == null) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "No device bound to this machine");
        }
        return deviceRepository.findByDeviceId(machine.getDeviceId())
                .orElseThrow(() -> new ProvisioningRejectedException(HttpStatus.CONFLICT, "Bound device record missing"));
    }

    private void requireFreshHeartbeat(Machine machine) {
        OffsetDateTime last = machine.getLastHeartbeatAt();
        if (!machine.isOnline() || last == null
                || last.isBefore(OffsetDateTime.now().minusSeconds(heartbeatTimeoutSeconds))) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Device is offline; it must be plugged in over USB with a recent heartbeat");
        }
    }
}
