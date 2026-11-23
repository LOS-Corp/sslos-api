package com.laundry.device;

import com.laundry.audit.AuditService;
import com.laundry.common.AfterCommit;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.device.link.DeviceLinkHub;
import com.laundry.locker.Locker;
import com.laundry.locker.LockerRepository;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineAvailabilityChanged;
import com.laundry.machine.MachineRepository;
import com.laundry.order.OrderLeg;
import com.laundry.order.OrderLegRepository;
import com.laundry.security.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Quản trị thiết bị (§2 Quản trị hệ thống): duyệt khóa, gắn vào máy/tủ, gỡ, thu hồi, xóa. */
@Service
@RequiredArgsConstructor
public class DeviceAdminService {

    private final DeviceRepository devices;
    private final MachineRepository machines;
    private final LockerRepository lockers;
    private final OrderLegRepository legs;
    private final MqttAccountService accounts;
    private final DeviceCommandRepository commandRepository;
    private final DeviceLinkHub hub;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public record DeviceView(String id, Device.Type type, Device.LinkState linkState, String keyFingerprint,
                             String firmwareVersion, UUID machineId, String machineCode, UUID lockerId,
                             String lockerCode, boolean online, boolean connected, Instant lastSeenAt, String lastIp,
                             Integer lastRssi, Instant approvedAt, Instant revokedAt, String revokeReason) {
    }

    @Transactional(readOnly = true)
    public List<DeviceView> list() {
        return devices.listAll().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public DeviceView get(String id) {
        return view(find(id));
    }

    /** Admin so dấu vân tay trên màn hình bo với {@code fingerprint} rồi mới duyệt. */
    @Transactional
    public DeviceView approve(String id, String fingerprint) {
        Device d = locked(id);
        if (d.getLinkState() != Device.LinkState.PENDING && d.getLinkState() != Device.LinkState.REVOKED) {
            throw new BusinessException(ErrorCode.DEVICE_STATE, "Bo không ở trạng thái chờ duyệt");
        }
        if (fingerprint == null || !fingerprint.trim().equalsIgnoreCase(d.getKeyFingerprint())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Dấu vân tay không khớp với màn hình bo");
        }
        d.setLinkState(Device.LinkState.VERIFIED);
        d.setApprovedBy(Actor.userId());
        d.setApprovedAt(clock.instant());
        d.setRevokedAt(null);
        d.setRevokeReason(null);
        audit.user("DEVICE_APPROVED", "device", id, "Duyệt khóa " + d.getKeyFingerprint(), null,
                Map.of("fingerprint", d.getKeyFingerprint()));
        notifyBoard(id);
        return view(d);
    }

    /** Gắn bo vào máy (WASHER/DRYER đúng loại) hoặc tủ locker. */
    @Transactional
    public DeviceView bind(String id, UUID machineId, UUID lockerId, String reason) {
        if ((machineId == null) == (lockerId == null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Chọn đúng một máy hoặc một tủ");
        }
        Device d = locked(id);
        if (d.getLinkState() != Device.LinkState.VERIFIED && d.getLinkState() != Device.LinkState.BOUND) {
            throw new BusinessException(ErrorCode.DEVICE_STATE, "Bo cần được duyệt trước khi gắn");
        }
        if (machineId != null) {
            Machine m = machines.findById(machineId).orElseThrow(() -> BusinessException.notFound("máy"));
            if (!d.getDeviceType().name().equals(m.getMachineType().name())) {
                throw new BusinessException(ErrorCode.MACHINE_TYPE_MISMATCH, "Bo " + d.getDeviceType() + " không gắn được vào máy "
                        + m.getMachineType());
            }
            devices.findByMachineId(machineId).filter(o -> !o.getId().equals(id)).ifPresent(o -> {
                throw new BusinessException(ErrorCode.CONFLICT, "Máy " + m.getCode() + " đã gắn bo " + o.getId());
            });
        } else {
            Locker l = lockers.findById(lockerId).orElseThrow(() -> BusinessException.notFound("tủ locker"));
            if (d.getDeviceType() != Device.Type.LOCKER) {
                throw new BusinessException(ErrorCode.MACHINE_TYPE_MISMATCH, "Chỉ bo LOCKER gắn được vào tủ");
            }
            devices.findByLockerId(lockerId).filter(o -> !o.getId().equals(id)).ifPresent(o -> {
                throw new BusinessException(ErrorCode.CONFLICT, "Tủ " + l.getCode() + " đã gắn bo " + o.getId());
            });
        }
        UUID oldMachine = d.getMachineId();
        if (oldMachine != null && !oldMachine.equals(machineId)) requireIdle(oldMachine);
        d.setMachineId(machineId);
        d.setLockerId(lockerId);
        d.setLinkState(Device.LinkState.BOUND);
        audit.user("DEVICE_BOUND", "device", id, Reasons.require(reason), Map.of("machineId", String.valueOf(oldMachine)),
                Map.of("machineId", String.valueOf(machineId), "lockerId", String.valueOf(lockerId)));
        if (oldMachine != null && !oldMachine.equals(machineId)) {
            events.publishEvent(new MachineAvailabilityChanged(oldMachine, false, "Gỡ bo mạch"));
        }
        notifyBoard(id);
        return view(d);
    }

    @Transactional
    public DeviceView unbind(String id, String reason) {
        Device d = locked(id);
        if (d.getLinkState() != Device.LinkState.BOUND) throw new BusinessException(ErrorCode.DEVICE_STATE, "Bo chưa gắn");
        UUID machineId = d.getMachineId();
        if (machineId != null) requireIdle(machineId);
        d.setMachineId(null);
        d.setLockerId(null);
        d.setLinkState(Device.LinkState.VERIFIED);
        audit.user("DEVICE_UNBOUND", "device", id, Reasons.require(reason), Map.of("machineId", String.valueOf(machineId)), null);
        if (machineId != null) events.publishEvent(new MachineAvailabilityChanged(machineId, false, "Gỡ bo mạch"));
        notifyBoard(id);
        return view(d);
    }

    /**
     * Thay thiết bị (máy thật) đang đứng ở một máy/tủ trong hệ thống bằng thiết bị dự phòng, trong một giao dịch:
     * máy trong hệ thống giữ nguyên mã, QR, giá, lịch đặt và không bị đánh dấu ngừng phục vụ; thiết bị cũ về trạng thái
     * đã duyệt, chưa gắn (đem đi bảo hành, sửa). Từ chối khi máy đang có khách.
     */
    @Transactional
    public DeviceView replace(String oldId, String newId, String rawReason) {
        String reason = Reasons.require(rawReason);
        if (oldId.equals(newId)) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Chọn thiết bị dự phòng khác thiết bị đang gắn");
        // Khóa theo thứ tự cố định để hai yêu cầu thay chéo nhau không deadlock.
        Device first = locked(oldId.compareTo(newId) < 0 ? oldId : newId);
        Device second = locked(oldId.compareTo(newId) < 0 ? newId : oldId);
        Device old = first.getId().equals(oldId) ? first : second;
        Device spare = old == first ? second : first;
        if (old.getLinkState() != Device.LinkState.BOUND) throw new BusinessException(ErrorCode.DEVICE_STATE, "Thiết bị " + oldId + " chưa gắn vào máy/tủ nào");
        if (spare.getLinkState() != Device.LinkState.VERIFIED) {
            throw new BusinessException(ErrorCode.DEVICE_STATE, spare.getLinkState() == Device.LinkState.BOUND
                    ? "Thiết bị dự phòng " + newId + " đang gắn ở chỗ khác; gỡ ra trước"
                    : "Thiết bị dự phòng " + newId + " cần được duyệt trước");
        }
        if (spare.getDeviceType() != old.getDeviceType()) {
            throw new BusinessException(ErrorCode.MACHINE_TYPE_MISMATCH, "Thiết bị " + spare.getDeviceType()
                    + " không thay được cho thiết bị " + old.getDeviceType());
        }
        UUID machineId = old.getMachineId(), lockerId = old.getLockerId();
        if (machineId != null) requireIdle(machineId);
        old.setMachineId(null);
        old.setLockerId(null);
        old.setLinkState(Device.LinkState.VERIFIED);
        devices.saveAndFlush(old); // machine_id/locker_id là UNIQUE: nhả chỗ trước khi thiết bị dự phòng nhận.
        spare.setMachineId(machineId);
        spare.setLockerId(lockerId);
        spare.setLinkState(Device.LinkState.BOUND);
        Map<String, String> target = Map.of("machineId", String.valueOf(machineId), "lockerId", String.valueOf(lockerId));
        audit.user("DEVICE_UNBOUND", "device", oldId, reason + " (thay bằng " + newId + ")", target, null);
        audit.user("DEVICE_BOUND", "device", newId, reason + " (thay cho " + oldId + ")", null, target);
        notifyBoard(oldId);
        notifyBoard(newId);
        return view(spare);
    }

    /** Máy có khách đang chờ bấm START, đang chạy hoặc chờ lấy đồ: không đổi thiết bị, kẻo lượt đó không còn ai báo kết thúc. */
    private void requireIdle(UUID machineId) {
        List<OrderLeg> busy = legs.onMachine(machineId, List.of(OrderLeg.Status.ACTIVATED, OrderLeg.Status.RUNNING,
                OrderLeg.Status.AWAITING_UNLOAD));
        if (!busy.isEmpty()) {
            String code = machines.findById(machineId).map(Machine::getCode).orElse("?");
            throw new BusinessException(ErrorCode.DEVICE_STATE, "Máy " + code + " đang có khách (chờ bấm START, đang chạy"
                    + " hoặc chờ lấy đồ). Đợi xong lượt, hoặc chuyển máy sang Bảo trì cho các đơn chưa chạy, rồi thử lại.");
        }
    }

    /** Thu hồi: tài khoản broker bị khóa, bo phải được duyệt lại. */
    @Transactional
    public DeviceView revoke(String id, String rawReason) {
        String reason = Reasons.require(rawReason);
        Device d = locked(id);
        UUID machineId = d.getMachineId();
        d.setMachineId(null);
        d.setLockerId(null);
        d.setLinkState(Device.LinkState.REVOKED);
        d.setRevokedBy(Actor.userId());
        d.setRevokedAt(clock.instant());
        d.setRevokeReason(reason);
        accounts.disable(id);
        audit.user("DEVICE_REVOKED", "device", id, reason, Map.of("machineId", String.valueOf(machineId)), null);
        if (machineId != null) events.publishEvent(new MachineAvailabilityChanged(machineId, false, "Thu hồi bo mạch"));
        notifyBoard(id);
        return view(d);
    }

    /** Xóa hẳn bo chưa gắn (ví dụ sau khi reset nhà máy, bo có khóa mới): lần đăng ký sau là bo mới, chờ duyệt. */
    @Transactional
    public void forget(String id, String reason) {
        Device d = locked(id);
        if (d.getLinkState() == Device.LinkState.BOUND) throw new BusinessException(ErrorCode.DEVICE_STATE, "Gỡ bo khỏi máy trước");
        audit.user("DEVICE_FORGOTTEN", "device", id, Reasons.require(reason),
                Map.of("fingerprint", d.getKeyFingerprint(), "state", d.getLinkState()), null);
        commandRepository.deleteByDevice(id);
        devices.delete(d);
        notifyBoard(id);
    }

    private void notifyBoard(String id) {
        AfterCommit.run(() -> hub.linkChanged(id));
    }

    private Device find(String id) {
        return devices.findById(id).orElseThrow(() -> BusinessException.notFound("thiết bị"));
    }

    private Device locked(String id) {
        return devices.lock(id).orElseThrow(() -> BusinessException.notFound("thiết bị"));
    }

    private DeviceView view(Device d) {
        String machineCode = d.getMachineId() == null ? null
                : machines.findById(d.getMachineId()).map(Machine::getCode).orElse(null);
        String lockerCode = d.getLockerId() == null ? null
                : lockers.findById(d.getLockerId()).map(Locker::getCode).orElse(null);
        return new DeviceView(d.getId(), d.getDeviceType(), d.getLinkState(), d.getKeyFingerprint(),
                d.getFirmwareVersion(), d.getMachineId(), machineCode, d.getLockerId(), lockerCode, d.isOnline(),
                hub.isConnected(d.getId()), d.getLastSeenAt(), d.getLastIp(), d.getLastRssi(), d.getApprovedAt(),
                d.getRevokedAt(), d.getRevokeReason());
    }
}
