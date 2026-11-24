package com.laundry.device;

import com.laundry.audit.AuditService;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.device.link.DeviceLinkHub;
import com.laundry.device.link.DeviceNotConnectedException;
import com.laundry.machine.Machine;
import com.laundry.machine.Machine.DeploymentStatus;
import com.laundry.machine.MachineAvailabilityChanged;
import com.laundry.machine.MachineRepository;
import com.laundry.order.OrderLeg;
import com.laundry.order.OrderLegRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Lắp đặt máy theo mã máy (W01): gắn bo -> self-test -> đưa vào hoạt động; bảo trì; hủy liên kết.
 * Kết quả self-test đến từ bo qua event SELF_TEST_RESULT; chỉ máy ACTIVE mới bán được ({@link Machine#sellable()}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MachineDeploymentService {

    /** Bước được chạy (lại) self-test; khớp SELF_TESTABLE của web quản lý. */
    static final Set<DeploymentStatus> SELF_TESTABLE = EnumSet.of(DeploymentStatus.PROVISIONED,
            DeploymentStatus.TEST_FAILED, DeploymentStatus.TEST_PASSED, DeploymentStatus.MAINTENANCE);
    /** Lượt còn mở trên máy: đang chờ START, đang chạy, chờ lấy đồ hoặc đã đặt chưa tới giờ. */
    private static final List<OrderLeg.Status> OPEN_LEGS = List.of(OrderLeg.Status.SCHEDULED,
            OrderLeg.Status.ACTIVATED, OrderLeg.Status.RUNNING, OrderLeg.Status.AWAITING_UNLOAD);
    /** Ngoài thời gian chờ bấm START, firmware cần thêm vài giây (còi, động cơ/quạt); quá hạn này thì coi như không có kết quả. */
    private static final long RESULT_GRACE_SECONDS = 90;
    static final String SELF_TEST = "SELF_TEST";

    private final MachineRepository machines;
    private final DeviceRepository devices;
    private final DeviceAdminService deviceAdmin;
    private final DeviceCommandService commands;
    private final OrderLegRepository legs;
    private final DeviceLinkHub hub;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Thời gian bo chờ kỹ thuật viên bấm START trong self-test (firmware giới hạn 5–120 giây). */
    @Value("${app.deployment.self-test-button-seconds:60}")
    private int buttonSeconds = 60;

    public record ProvisioningView(String machineCode, DeploymentStatus deploymentStatus, String deviceId,
                                   String deviceStatus, boolean deviceConnected, boolean online,
                                   Instant lastHeartbeatAt, String firmwareVersion, Instant lastSelfTestAt,
                                   Boolean lastSelfTestPassed, String lastSelfTestDetail, Instant maintenanceSince,
                                   Instant commissionedAt) {
    }

    /** {@code commandId} dạng SELFTEST-&lt;uuid&gt;; uuid là id dòng device_commands (bo nhận và trả lại uuid trần). */
    public record SelfTestReceipt(String commandId, int buttonTimeoutSeconds, String machineCode, String deviceId) {
    }

    @Transactional(readOnly = true)
    public ProvisioningView provisioning(String code) {
        return view(byCode(code));
    }

    /** Gắn bo đã duyệt vào máy chưa có bo; self-test tự chạy khi bo gửi heartbeat đầu tiên (xem {@link #onSnapshot}). */
    @Transactional
    public ProvisioningView pair(String code, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Chọn thiết bị");
        Machine m = locked(code);
        if (m.getDeploymentStatus() != DeploymentStatus.UNASSIGNED || boundDevice(m.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.DEVICE_STATE, "Máy " + m.getCode() + " đã gắn thiết bị; hủy liên kết trước");
        }
        Device d = devices.findById(deviceId.trim()).orElseThrow(() -> BusinessException.notFound("thiết bị"));
        // Chỉ nhận bo đã duyệt, chưa gắn: chuyển bo đang chạy ở máy khác phải đi qua hủy liên kết / thay thiết bị.
        if (d.getLinkState() != Device.LinkState.VERIFIED) {
            throw new BusinessException(ErrorCode.DEVICE_STATE, d.getLinkState() == Device.LinkState.BOUND
                    ? "Thiết bị " + d.getId() + " đang gắn ở máy/tủ khác" : "Thiết bị " + d.getId() + " cần được duyệt trước");
        }
        deviceAdmin.bind(d.getId(), m.getId(), null, "Gắn thiết bị từ mục Lắp đặt máy " + m.getCode());
        return view(m);
    }

    /** Yêu cầu bo tự kiểm tra; HTTP thành công chỉ xác nhận lệnh đã gửi, kết quả về sau qua SELF_TEST_RESULT. */
    @Transactional(noRollbackFor = DeviceNotConnectedException.class)
    public SelfTestReceipt selfTest(String code) {
        Machine m = locked(code);
        if (!SELF_TESTABLE.contains(m.getDeploymentStatus())) {
            throw new BusinessException(ErrorCode.DEVICE_STATE, m.getDeploymentStatus() == DeploymentStatus.TESTING
                    ? "Máy " + m.getCode() + " đang self-test; chờ kết quả"
                    : "Máy " + m.getCode() + " không ở bước chạy self-test (" + m.getDeploymentStatus() + ")");
        }
        DeviceCommand c = sendSelfTest(m, DeviceCommand.Source.STAFF, "Self-test lắp đặt máy " + m.getCode());
        audit.user("MACHINE_SELF_TEST_REQUESTED", "machine", m.getId().toString(), "Self-test lắp đặt",
                null, Map.of("commandId", c.getId().toString(), "deviceId", c.getDeviceId()));
        return new SelfTestReceipt("SELFTEST-" + c.getId(), buttonSeconds, m.getCode(), c.getDeviceId());
    }

    /** Đưa vào hoạt động (hoặc hoàn tất bảo trì): cần self-test gần nhất đạt và bo đang kết nối. */
    @Transactional
    public ProvisioningView commission(String code) {
        Machine m = locked(code);
        if (m.getDeploymentStatus() != DeploymentStatus.TEST_PASSED || !Boolean.TRUE.equals(m.getLastSelfTestPassed())) {
            throw new BusinessException(ErrorCode.DEVICE_STATE, "Máy " + m.getCode() + " cần self-test đạt trước khi đưa vào hoạt động");
        }
        Device d = boundDevice(m.getId()).orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_STATE,
                "Máy " + m.getCode() + " chưa gắn thiết bị"));
        if (!hub.isConnected(d.getId())) throw new DeviceNotConnectedException("Thiết bị của máy " + m.getCode() + " đang ngoại tuyến");
        boolean fromMaintenance = m.getMaintenanceSince() != null;
        Instant now = clock.instant();
        m.setDeploymentStatus(DeploymentStatus.ACTIVE);
        m.setMaintenanceSince(null);
        if (m.getCommissionedAt() == null) m.setCommissionedAt(now);
        audit.user(fromMaintenance ? "MACHINE_MAINTENANCE_FINISHED" : "MACHINE_COMMISSIONED", "machine",
                m.getId().toString(), fromMaintenance ? "Hoàn tất bảo trì" : "Đưa vào hoạt động",
                null, Map.of("deviceId", d.getId(), "selfTestAt", String.valueOf(m.getLastSelfTestAt())));
        events.publishEvent(new MachineAvailabilityChanged(m.getId(), true, "Máy " + m.getCode() + " vào hoạt động"));
        return view(m);
    }

    /** Tạm ngừng cho thuê máy đang ACTIVE để sửa; từ chối khi còn lượt mở trên máy. Quay lại bằng self-test rồi commission. */
    @Transactional
    public ProvisioningView startMaintenance(String code, String rawReason) {
        String reason = rawReason == null || rawReason.isBlank() ? "Bảo trì từ mục Lắp đặt" : Reasons.require(rawReason);
        Machine m = locked(code);
        if (m.getDeploymentStatus() != DeploymentStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.DEVICE_STATE, "Chỉ máy đang hoạt động mới đưa vào bảo trì được");
        }
        requireNoOpenLegs(m, "đưa vào bảo trì");
        m.setDeploymentStatus(DeploymentStatus.MAINTENANCE);
        m.setMaintenanceSince(clock.instant());
        audit.user("MACHINE_MAINTENANCE_STARTED", "machine", m.getId().toString(), reason,
                Map.of("deploymentStatus", DeploymentStatus.ACTIVE), Map.of("deploymentStatus", DeploymentStatus.MAINTENANCE));
        events.publishEvent(new MachineAvailabilityChanged(m.getId(), false, reason));
        return view(m);
    }

    /** Hủy liên kết: thu hồi bo của máy (phải được duyệt lại mới dùng được), máy về chưa gán thiết bị. */
    @Transactional
    public void unpair(String code) {
        Machine m = locked(code);
        Device d = boundDevice(m.getId()).orElseThrow(() -> new BusinessException(ErrorCode.DEVICE_STATE,
                "Máy " + m.getCode() + " chưa gắn thiết bị"));
        requireNoOpenLegs(m, "hủy liên kết thiết bị");
        deviceAdmin.revoke(d.getId(), "Hủy liên kết từ mục Lắp đặt máy " + m.getCode());
    }

    // ---- Tin từ bo ----

    /** Heartbeat/status đầu tiên sau khi gắn bo: tự gửi self-test (máy PROVISIONED, chưa từng gửi). */
    @EventListener
    public void onSnapshot(DeviceEvents.Snapshot e) {
        if (e.machineId() == null) return;
        Optional<Machine> found = machines.findById(e.machineId());
        if (found.isEmpty() || !awaitingFirstTest(found.get())) return;
        Machine m = machines.lock(e.machineId()).orElseThrow();
        if (!awaitingFirstTest(m)) return;
        try {
            sendSelfTest(m, DeviceCommand.Source.SYSTEM, null);
            audit.system("MACHINE_SELF_TEST_REQUESTED", "machine", m.getId().toString(), "Tự chạy sau khi gắn thiết bị",
                    null, Map.of("commandId", m.getSelfTestCommandId().toString(), "deviceId", e.deviceId()));
        } catch (DeviceNotConnectedException ex) {
            log.warn("[DEPLOY] auto self-test for {} not sent: {}", m.getCode(), ex.getMessage());
        }
    }

    @EventListener
    public void onEvent(DeviceEvents.Event e) {
        if (e.machineId() == null || !"SELF_TEST_RESULT".equals(e.type())) return;
        Machine m = machines.lock(e.machineId()).orElse(null);
        if (m == null) return;
        JsonNode b = e.body();
        UUID commandId = DeviceInbound.uuid(DeviceInbound.text(b, "commandId"));
        boolean current = commandId != null && commandId.equals(m.getSelfTestCommandId());
        // Kết quả của lệnh cũ (đã có lệnh mới) bỏ qua; kết quả trễ sau khi đã báo hết hạn vẫn nhận nếu đúng lệnh.
        boolean accept = m.getDeploymentStatus() == DeploymentStatus.TESTING ? commandId == null || current
                : current && m.getDeploymentStatus() == DeploymentStatus.TEST_FAILED;
        if (!accept) {
            log.info("[DEPLOY] {} ignored SELF_TEST_RESULT {} in {}", m.getCode(), commandId, m.getDeploymentStatus());
            return;
        }
        JsonNode checks = b.path("checks");
        String detail = mapper.writeValueAsString(checks.isObject() ? checks : mapper.createObjectNode());
        record(m, b.path("passed").asBoolean(false), detail);
        audit.device(e.deviceId(), "MACHINE_SELF_TEST_RESULT", "machine", m.getId().toString(),
                Map.of("passed", m.getLastSelfTestPassed(), "commandId", String.valueOf(commandId)));
    }

    /** Bo từ chối lệnh SELF_TEST (ví dụ BUSY): kết thúc lượt kiểm tra là không đạt kèm lý do. */
    @EventListener
    public void onAck(DeviceEvents.CommandAck e) {
        if (e.success() || e.commandId() == null || !SELF_TEST.equals(e.command())) return;
        machines.findBySelfTestCommandId(e.commandId()).flatMap(x -> machines.lock(x.getId()))
                .filter(m -> m.getDeploymentStatus() == DeploymentStatus.TESTING && e.commandId().equals(m.getSelfTestCommandId()))
                .ifPresent(m -> record(m, false, json(Map.of("reason", e.reason() == null || e.reason().isBlank()
                        ? "REJECTED" : e.reason()))));
    }

    /** Self-test không có kết quả sau thời gian chờ: không đạt (NO_RESULT), cho chạy lại. */
    @Transactional
    public int timeoutSelfTests() {
        Instant before = clock.instant().minusSeconds(buttonSeconds + RESULT_GRACE_SECONDS);
        List<Machine> stale = machines.findByDeploymentStatusAndSelfTestRequestedAtBefore(DeploymentStatus.TESTING, before);
        for (Machine x : stale) {
            machines.lock(x.getId()).filter(m -> m.getDeploymentStatus() == DeploymentStatus.TESTING)
                    .ifPresent(m -> record(m, false, json(Map.of("reason", "NO_RESULT"))));
        }
        return stale.size();
    }

    // ---- Nội bộ ----

    private boolean awaitingFirstTest(Machine m) {
        return m.getDeploymentStatus() == DeploymentStatus.PROVISIONED && m.getSelfTestCommandId() == null
                && m.getLastSelfTestAt() == null;
    }

    /** Gửi SELF_TEST rồi chuyển máy sang TESTING; bo offline thì ném lỗi và máy giữ nguyên bước. */
    private DeviceCommand sendSelfTest(Machine m, DeviceCommand.Source source, String reason) {
        DeviceCommand c = commands.send(commands.machineTarget(m.getId()), new DeviceCommandService.Spec(SELF_TEST,
                Map.of("buttonTimeoutSeconds", buttonSeconds), source, reason, null, null, null));
        m.setDeploymentStatus(DeploymentStatus.TESTING);
        m.setSelfTestCommandId(c.getId());
        m.setSelfTestRequestedAt(clock.instant());
        return c;
    }

    private void record(Machine m, boolean passed, String detail) {
        m.setLastSelfTestAt(clock.instant());
        m.setLastSelfTestPassed(passed);
        m.setLastSelfTestDetail(detail);
        m.setDeploymentStatus(passed ? DeploymentStatus.TEST_PASSED : DeploymentStatus.TEST_FAILED);
    }

    private void requireNoOpenLegs(Machine m, String action) {
        if (!legs.onMachine(m.getId(), OPEN_LEGS).isEmpty()) {
            throw new BusinessException(ErrorCode.DEVICE_STATE, "Máy " + m.getCode() + " còn lượt đã đặt, đang chờ START,"
                    + " đang chạy hoặc chờ lấy đồ; không thể " + action + ". Đợi xong hoặc chuyển các lượt sang máy khác rồi thử lại.");
        }
    }

    private Optional<Device> boundDevice(UUID machineId) {
        return devices.findByMachineId(machineId).filter(d -> d.getLinkState() == Device.LinkState.BOUND);
    }

    private Machine byCode(String code) {
        return machines.findByCode(code == null ? "" : code.trim()).orElseThrow(() -> BusinessException.notFound("máy"));
    }

    private Machine locked(String code) {
        return machines.lock(byCode(code).getId()).orElseThrow(() -> BusinessException.notFound("máy"));
    }

    private String json(Map<String, Object> value) {
        return mapper.writeValueAsString(value);
    }

    private ProvisioningView view(Machine m) {
        Device d = boundDevice(m.getId()).orElse(null);
        if (d == null) {
            return new ProvisioningView(m.getCode(), m.getDeploymentStatus(), null, null, false, false, null, null,
                    m.getLastSelfTestAt(), m.getLastSelfTestPassed(), m.getLastSelfTestDetail(), m.getMaintenanceSince(),
                    m.getCommissionedAt());
        }
        String deviceStatus = m.getDeploymentStatus() == DeploymentStatus.ACTIVE ? "ACTIVE" : "PROVISIONED";
        return new ProvisioningView(m.getCode(), m.getDeploymentStatus(), d.getId(), deviceStatus,
                hub.isConnected(d.getId()), d.isOnline(), d.getLastSeenAt(), d.getFirmwareVersion(), m.getLastSelfTestAt(),
                m.getLastSelfTestPassed(), m.getLastSelfTestDetail(), m.getMaintenanceSince(), m.getCommissionedAt());
    }
}
