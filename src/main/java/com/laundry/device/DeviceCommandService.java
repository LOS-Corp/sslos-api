package com.laundry.device;

import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.device.link.DeviceFrame;
import com.laundry.device.link.DeviceLinkHub;
import com.laundry.device.link.DeviceNotConnectedException;
import com.laundry.audit.AuditService;
import com.laundry.locker.Locker;
import com.laundry.locker.LockerRepository;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineRepository;
import com.laundry.security.Actor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mọi lệnh xuống bo đi qua đây: lưu device_commands rồi gửi down/command.
 * Lệnh của nhân viên bắt buộc có lý do và ghi nhật ký (§2, §16).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceCommandService {

    /** Lệnh nhân viên được gửi trực tiếp; kích hoạt máy / mở ô đi qua module đặt lượt / locker. */
    public static final Set<String> STAFF_MACHINE_COMMANDS = Set.of("STOP", "RESET", "STATUS", "SELF_TEST");

    private final DeviceCommandRepository commands;
    private final DeviceRepository devices;
    private final MachineRepository machines;
    private final LockerRepository lockers;
    private final DeviceLinkHub hub;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public record Target(String deviceId, String code) {
    }

    /** {@code id}: đặt sẵn commandId (null = tự sinh). */
    public record Spec(UUID id, String type, Map<String, Object> fields, DeviceCommand.Source source, String reason,
                       UUID orderLegId, UUID lockerAssignmentId, UUID kioskId) {
        public Spec(String type, Map<String, Object> fields, DeviceCommand.Source source, String reason,
                    UUID orderLegId, UUID lockerAssignmentId, UUID kioskId) {
            this(null, type, fields, source, reason, orderLegId, lockerAssignmentId, kioskId);
        }

        public static Spec system(String type, Map<String, Object> fields) {
            return new Spec(null, type, fields, DeviceCommand.Source.SYSTEM, null, null, null, null);
        }
    }

    public java.util.Optional<DeviceCommand> find(UUID id) {
        return id == null ? java.util.Optional.empty() : commands.findById(id);
    }

    public Target machineTarget(UUID machineId) {
        Machine m = machines.findById(machineId).orElseThrow(() -> BusinessException.notFound("máy"));
        Device d = devices.findByMachineId(machineId).filter(x -> x.getLinkState() == Device.LinkState.BOUND)
                .orElseThrow(() -> new DeviceNotConnectedException("Máy " + m.getCode() + " chưa gắn bo mạch"));
        return new Target(d.getId(), m.getCode());
    }

    public Target lockerTarget(UUID lockerId) {
        Locker l = lockers.findById(lockerId).orElseThrow(() -> BusinessException.notFound("tủ locker"));
        Device d = devices.findByLockerId(lockerId).filter(x -> x.getLinkState() == Device.LinkState.BOUND)
                .orElseThrow(() -> new DeviceNotConnectedException("Tủ " + l.getCode() + " chưa gắn bo mạch"));
        return new Target(d.getId(), l.getCode());
    }

    public boolean isOnline(UUID machineId) {
        return devices.findByMachineId(machineId).filter(d -> d.getLinkState() == Device.LinkState.BOUND)
                .map(d -> hub.isConnected(d.getId())).orElse(false);
    }

    /**
     * Lưu và gửi lệnh. {@code commandId} = id dòng device_commands (trừ khi {@code fields} đã có).
     * Ném {@link DeviceNotConnectedException} khi bo không online (dòng lệnh vẫn được lưu với FAILED).
     */
    @Transactional(noRollbackFor = DeviceNotConnectedException.class)
    public DeviceCommand send(Target target, Spec spec) {
        DeviceCommand c = new DeviceCommand();
        if (spec.id() != null) c.setId(spec.id());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", spec.type());
        payload.put("commandId", c.getId().toString());
        payload.put("machineId", target.code());
        if (spec.fields() != null) payload.putAll(spec.fields());
        String json = mapper.writeValueAsString(payload);
        c.setDeviceId(target.deviceId());
        c.setCommandType(spec.type());
        c.setPayload(json);
        c.setOrderLegId(spec.orderLegId());
        c.setLockerAssignmentId(spec.lockerAssignmentId());
        c.setSource(spec.source());
        c.setKioskId(spec.kioskId());
        c.setReason(spec.reason());
        if (spec.source() == DeviceCommand.Source.STAFF) c.setRequestedBy(Actor.userId());
        c.setAttempts(1);
        Instant now = clock.instant();
        try {
            hub.send(target.deviceId(), DeviceFrame.COMMAND, json);
            c.setStatus(DeviceCommand.Status.SENT);
            c.setSentAt(now);
            commands.save(c);
            return c;
        } catch (DeviceNotConnectedException e) {
            c.setStatus(DeviceCommand.Status.FAILED);
            commands.save(c);
            throw e;
        }
    }

    /** Như {@link #send} nhưng không ném lỗi khi bo offline; trả false. */
    @Transactional
    public boolean trySend(Target target, Spec spec) {
        try {
            send(target, spec);
            return true;
        } catch (DeviceNotConnectedException e) {
            log.warn("[CMD] {} not sent to {}: {}", spec.type(), target.deviceId(), e.getMessage());
            return false;
        }
    }

    /** Lệnh tay của nhân viên (dừng, khởi động lại, kiểm tra), bắt buộc lý do. */
    @Transactional
    public DeviceCommand staffMachineCommand(UUID machineId, String type, String rawReason) {
        String reason = Reasons.require(rawReason);
        if (!STAFF_MACHINE_COMMANDS.contains(type)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Lệnh không hợp lệ: " + type);
        }
        Target target = machineTarget(machineId);
        audit.user("MACHINE_COMMAND", "machine", machineId.toString(), reason, null, Map.of("command", type));
        return send(target, new Spec(type, Map.of(), DeviceCommand.Source.STAFF, reason, null, null, null));
    }

    /** Gửi lệnh cấu hình mạng (down/netcfg). */
    public void network(String deviceId, String action) {
        hub.send(deviceId, DeviceFrame.NETWORK, mapper.writeValueAsString(Map.of("action", action)));
    }

    /** Lệnh đã gửi mà không thấy trả lời sau 1 phút: TIMEOUT. */
    @Transactional
    public int timeoutStale() {
        return commands.timeoutOlderThan(clock.instant().minusSeconds(60));
    }
}
