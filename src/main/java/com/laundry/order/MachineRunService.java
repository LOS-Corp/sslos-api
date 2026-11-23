package com.laundry.order;

import com.laundry.audit.AuditService;
import com.laundry.common.Money;
import com.laundry.device.DeviceCommand;
import com.laundry.device.DeviceCommandService;
import com.laundry.device.DeviceEvents;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineAvailabilityChanged;
import com.laundry.machine.MachineRepository;
import com.laundry.notification.Notification;
import com.laundry.notification.NotificationService;
import com.laundry.refund.Refund;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Máy chạy (§9, §10): vòng START với bo (START_REQUEST -> START_APPROVAL trong 5 s -> STARTED), máy xong, khách tự
 * dừng, máy hỏng giữa chừng; combo: máy giặt xong thì tự kích hoạt máy sấy, khách có 10' chuyển đồ.
 * "Đang chạy" chỉ khi máy báo STARTED, kích hoạt chưa đủ (§9).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MachineRunService {

    private static final Set<String> IDLE_PHASES = Set.of("IDLE", "AVAILABLE");

    private final OrderRepository orders;
    private final OrderLegRepository legs;
    private final MachineRepository machines;
    private final BookingSupport support;
    private final BookingService booking;
    private final ActivationService activation;
    private final ReassignmentService reassignment;
    private final DeviceCommandService commands;
    private final NotificationService notifications;
    private final OrderEventLog events;
    private final AuditService audit;
    private final ApplicationEventPublisher publisher;
    private final Clock clock;

    // ==================================================================================
    // Kênh auth
    // ==================================================================================

    @EventListener
    public void onAuth(DeviceEvents.Auth e) {
        if (e.machineId() == null) return;
        JsonNode b = e.body();
        UUID authorizationId = uuid(b.path("authorizationId").asString(null));
        switch (e.type()) {
            case "START_REQUEST" -> startRequest(e, authorizationId, b);
            case "STARTED" -> started(e, authorizationId);
            case "AUTH_ACK" -> {
                if (!b.path("success").asBoolean(false)) activationEnded(e, authorizationId,
                        "Máy từ chối kích hoạt: " + b.path("reason").asString(""));
            }
            case "START_REJECTED", "START_FAILED", "AUTH_EXPIRED" ->
                    activationEnded(e, authorizationId, e.type() + " " + b.path("reason").asString(""));
            case "LOAD_WEIGHED" -> loadWeighed(e, authorizationId);
            default -> log.debug("[RUN] {} auth {} ignored", e.deviceId(), e.type());
        }
    }

    /** Khách bấm START: duyệt nếu kích hoạt còn hiệu lực và đơn đã trả đủ. Bo chờ tối đa 5 s. */
    private void startRequest(DeviceEvents.Auth e, UUID authorizationId, JsonNode b) {
        Optional<OrderLeg> found = legOf(e, authorizationId);
        Instant now = clock.instant();
        String denied = null;
        OrderLeg leg = found.orElse(null);
        Order o = leg == null ? null : orders.findById(leg.getOrderId()).orElse(null);
        if (leg == null || o == null) denied = "UNKNOWN_AUTHORIZATION";
        else if (leg.getStatus() != OrderLeg.Status.ACTIVATED) denied = "NOT_ACTIVATED";
        else if (leg.getActivationExpiresAt() == null || now.isAfter(leg.getActivationExpiresAt().plusSeconds(5))) denied = "EXPIRED";
        else if (!o.fullyPaid()) denied = "NOT_PAID";
        else if (!o.isOpen()) denied = "ORDER_CLOSED";
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("authorizationId", String.valueOf(authorizationId));
        fields.put("startRequestId", b.path("startRequestId").asString(""));
        if (denied == null) fields.put("runId", leg.getId().toString());
        fields.put("result", denied == null ? "APPROVED" : "DENIED");
        fields.put("reason", denied == null ? "" : denied);
        fields.put("bootId", b.path("bootId").asString(""));
        fields.put("validForMs", 5000);
        commands.trySend(new DeviceCommandService.Target(e.deviceId(), b.path("machineId").asString("")),
                new DeviceCommandService.Spec("START_APPROVAL", fields, DeviceCommand.Source.SYSTEM, null,
                        leg == null ? null : leg.getId(), null, null));
        if (leg != null) events.device(leg.getOrderId(), denied == null ? "START_APPROVED" : "START_DENIED",
                e.deviceId(), denied == null ? Map.of() : Map.of("reason", denied));
    }

    @Transactional
    public void started(DeviceEvents.Auth e, UUID authorizationId) {
        OrderLeg leg = legOf(e, authorizationId).flatMap(l -> legs.lock(l.getId())).orElse(null);
        if (leg == null || (leg.getStatus() != OrderLeg.Status.ACTIVATED && leg.getStatus() != OrderLeg.Status.SCHEDULED)) return;
        Order o = orders.lock(leg.getOrderId()).orElseThrow();
        OrderPolicy p = support.policy(o);
        Instant now = clock.instant();
        leg.setStatus(OrderLeg.Status.RUNNING);
        leg.setStartedAt(now);
        leg.setExpectedEndAt(now.plus(Duration.ofMinutes(leg.getCycleMinutes() + leg.getExtraMinutes())));
        // Khách đến đúng giờ thì máy xong sớm hơn: trả phần thời gian trống cho kiosk bán (§6.2).
        support.tryResize(leg, support.reservation(leg).getStartsAt(),
                Timeline.endAfterStart(now, leg.getCycleMinutes(), leg.getExtraMinutes(), p));
        Order.Status before = o.getStatus();
        if (o.getStatus() == Order.Status.CONFIRMED) o.setStatus(Order.Status.IN_PROGRESS);
        Instant ending = leg.getExpectedEndAt().minus(Duration.ofMinutes(5));
        if (ending.isAfter(now)) {
            notifications.order(o, Notification.Type.CYCLE_ENDING, "Máy sắp xong",
                    "Máy " + machineCode(leg) + " còn 5 phút.", ending);
        }
        events.record(o.getId(), "LEG_RUNNING", before, o.getStatus(), Map.of("legNo", leg.getLegNo(),
                "machine", machineCode(leg), "expectedEnd", leg.getExpectedEndAt().toString()));
    }

    /** Kích hoạt hết hiệu lực trên máy (hết 3 phút, máy từ chối...): khách quét lại QR để kích hoạt lại. */
    @Transactional
    public void activationEnded(DeviceEvents.Auth e, UUID authorizationId, String why) {
        legOf(e, authorizationId).flatMap(l -> legs.lock(l.getId()))
                .filter(l -> l.getStatus() == OrderLeg.Status.ACTIVATED)
                .filter(l -> isLatestAuthorization(l, authorizationId))
                .ifPresent(l -> {
                    l.setActivationExpiresAt(clock.instant());
                    if (!isComboDryer(l)) l.setStatus(OrderLeg.Status.SCHEDULED);
                    events.device(l.getOrderId(), "ACTIVATION_ENDED", e.deviceId(), Map.of("why", why.trim()));
                });
    }

    /** Máy giặt có cân: tự phục vụ không cân đồ (§4.1), luôn cho chạy. */
    private void loadWeighed(DeviceEvents.Auth e, UUID authorizationId) {
        OrderLeg leg = legOf(e, authorizationId).orElse(null);
        long seconds = leg == null || leg.getActivationExpiresAt() == null ? 60
                : Math.max(10, Duration.between(clock.instant(), leg.getActivationExpiresAt()).toSeconds());
        commands.trySend(new DeviceCommandService.Target(e.deviceId(), e.body().path("machineId").asString("")),
                DeviceCommandService.Spec.system("LOAD_VERDICT", Map.of("authorizationId", String.valueOf(authorizationId),
                        "accepted", true, "reason", "", "expiresInSeconds", seconds)));
    }

    // ==================================================================================
    // Trạng thái máy
    // ==================================================================================

    @EventListener
    @Transactional
    public void onSnapshot(DeviceEvents.Snapshot e) {
        if (e.machineId() == null) return;
        JsonNode b = e.body();
        String status = b.path("status").asString("").toUpperCase();
        String phase = b.path("phase").asString("").toUpperCase();
        boolean running = b.path("running").asBoolean(false);
        List<OrderLeg> runningLegs = legs.onMachine(e.machineId(), List.of(OrderLeg.Status.RUNNING));
        Instant now = clock.instant();
        for (OrderLeg l : runningLegs) {
            OrderLeg leg = legs.lock(l.getId()).orElseThrow();
            String runId = b.path("runId").asString("");
            if (!runId.isEmpty() && !runId.equals(leg.getId().toString())) continue;
            if ("COMPLETED".equals(phase) || "COMPLETED".equals(status)) {
                finish(leg, false);
            } else if ("ERROR".equals(status)) {
                deviceFault(leg, b.path("error").asString("Máy báo lỗi"));
            } else if (!running && IDLE_PHASES.contains(phase) && leg.getStartedAt() != null
                    && leg.getStartedAt().isBefore(now.minusSeconds(30))) {
                finish(leg, true);   // khách tự dừng giữa chừng: lượt coi như đã dùng, không hoàn (§9)
            } else if (running && b.has("remainingSeconds")) {
                leg.setExpectedEndAt(now.plusSeconds(b.path("remainingSeconds").asLong(0)));
            }
        }
    }

    @EventListener
    @Transactional
    public void onEvent(DeviceEvents.Event e) {
        if (e.machineId() == null) return;
        if ("STATE_CHANGED".equals(e.type()) && "COMPLETED".equalsIgnoreCase(e.body().path("phase").asString(""))) {
            String runId = e.body().path("runId").asString("");
            for (OrderLeg l : legs.onMachine(e.machineId(), List.of(OrderLeg.Status.RUNNING))) {
                if (runId.isEmpty() || runId.equals(l.getId().toString())) finish(legs.lock(l.getId()).orElseThrow(), false);
            }
        }
    }

    /** Máy chạy xong: khách có 10' lấy đồ; combo thì mở máy sấy (§9, §10.1). */
    void finish(OrderLeg leg, boolean stoppedByCustomer) {
        if (leg.getStatus() != OrderLeg.Status.RUNNING) return;
        Order o = orders.lock(leg.getOrderId()).orElseThrow();
        OrderPolicy p = support.policy(o);
        Instant now = clock.instant();
        leg.setStatus(OrderLeg.Status.AWAITING_UNLOAD);
        leg.setFinishedAt(now);
        leg.setStoppedByCustomer(stoppedByCustomer);
        support.tryResize(leg, support.reservation(leg).getStartsAt(), Timeline.endAfterFinish(now, p));
        notifications.order(o, Notification.Type.CYCLE_DONE, "Máy đã xong",
                "Máy " + machineCode(leg) + " đã xong, mời lấy đồ trong " + p.unloadMinutes() + " phút.", null);
        events.record(o.getId(), stoppedByCustomer ? "LEG_STOPPED_BY_CUSTOMER" : "LEG_FINISHED", o.getStatus(),
                o.getStatus(), Map.of("legNo", leg.getLegNo(), "machine", machineCode(leg)));
        if (o.getService() == Order.Service.WASH_DRY && leg.getLegNo() == 1) {
            legs.findByOrderIdOrderByLegNo(o.getId()).stream().filter(l -> l.getLegNo() == 2)
                    .filter(OrderLeg::notStarted).findFirst().ifPresent(dryer -> openDryer(o, dryer, p, now));
        }
    }

    /** Combo: máy sấy được giữ từ lúc máy giặt xong; khách có {@code transferMinutes} để bấm START (§9). */
    private void openDryer(Order o, OrderLeg dryer, OrderPolicy p, Instant now) {
        Machine m = machines.findById(dryer.getMachineId()).orElseThrow();
        if (m.getAdminStatus() != Machine.AdminStatus.ACTIVE || !commands.isOnline(m.getId())) {
            if (!reassignment.reassign(o, dryer, "Máy sấy " + m.getCode() + " không dùng được")) return;
        }
        var r = support.reservation(dryer);
        Instant from = r.getStartsAt().isAfter(now) ? r.getStartsAt() : now;
        // Máy sấy rảnh sớm hơn dự kiến: dời khoảng giữ về hiện tại (không kéo dài).
        if (r.getStartsAt().isAfter(now) && support.snapshot(List.of(dryer.getMachineId()), now, r.getStartsAt())
                .free(dryer.getMachineId(), now, r.getStartsAt(), o.getId())) {
            Duration length = Duration.ofMinutes(p.transferMinutes() + dryer.getCycleMinutes() + p.unloadMinutes()
                    + p.cleanupMinutes());
            if (support.tryResize(dryer, now, now.plus(length))) from = now;
        }
        dryer.setStartDeadlineAt(from.plus(Duration.ofMinutes(p.transferMinutes())));
        if (!from.isAfter(now.plusSeconds(30))) activateDryer(o, dryer, now);
    }

    /** Gọi từ openDryer hoặc từ job khi máy sấy tới lượt. */
    void activateDryer(Order o, OrderLeg dryer, Instant now) {
        int minutes = (int) Math.max(1, Duration.between(now, dryer.getStartDeadlineAt()).toMinutes());
        ActivationService.Result r = activation.activate(o, dryer, DeviceCommand.Source.SYSTEM, null, null, minutes);
        if (r.ok()) {
            notifications.order(o, Notification.Type.CYCLE_DONE, "Mời chuyển đồ sang máy sấy",
                    "Máy sấy " + r.machineCode() + " đã sẵn sàng; bấm START trong " + minutes + " phút.", null);
        } else {
            events.record(o.getId(), "ACTIVATION_FAILED", o.getStatus(), o.getStatus(), Map.of("problem", r.problem()));
        }
    }

    /** Máy hỏng giữa chừng (§12): nhân viên giúp lấy đồ; khách chọn chạy lại miễn phí hoặc hoàn 100% lượt đó. */
    private void deviceFault(OrderLeg leg, String error) {
        Order o = orders.lock(leg.getOrderId()).orElseThrow();
        leg.setStatus(OrderLeg.Status.INTERRUPTED);
        leg.setInterruptionReason("Máy báo lỗi: " + error);
        leg.setFinishedAt(clock.instant());
        Machine m = machines.lock(leg.getMachineId()).orElseThrow();
        if (m.getAdminStatus() == Machine.AdminStatus.ACTIVE) {
            m.setAdminStatus(Machine.AdminStatus.ERROR);
            m.setStatusReason("Máy tự báo lỗi: " + error);
            m.setStatusChangedAt(clock.instant());
            audit.system("MACHINE_STATUS_CHANGED", "machine", m.getId().toString(), "Máy tự báo lỗi: " + error,
                    Map.of("status", "ACTIVE"), Map.of("status", "ERROR"));
            publisher.publishEvent(new MachineAvailabilityChanged(m.getId(), false, "Máy " + m.getCode() + " báo lỗi"));
        }
        events.record(o.getId(), "LEG_INTERRUPTED", o.getStatus(), o.getStatus(),
                Map.of("legNo", leg.getLegNo(), "machine", m.getCode(), "error", error));
    }

    // ==================================================================================
    // Job
    // ==================================================================================

    /** Hết thời hạn bấm START (chặng đầu): khách quét lại QR được tới hết ân hạn (§8.3). */
    @Transactional
    public void expireActivation(UUID legId) {
        OrderLeg leg = legs.lock(legId).orElse(null);
        if (leg == null || leg.getStatus() != OrderLeg.Status.ACTIVATED || leg.getActivationExpiresAt() == null
                || leg.getActivationExpiresAt().isAfter(clock.instant())) return;
        if (isComboDryer(leg)) {
            transferExpired(leg);
            return;
        }
        leg.setStatus(OrderLeg.Status.SCHEDULED);
        events.record(leg.getOrderId(), "ACTIVATION_EXPIRED", null, null, Map.of("legNo", leg.getLegNo()));
    }

    /**
     * Combo, quá thời gian chuyển đồ: máy sấy không vướng lượt sau thì giữ thêm 10' (*); hết thời gian giữ thêm thì hủy
     * chặng sấy, hoàn 80% giá chặng sấy (§9).
     */
    private void transferExpired(OrderLeg dryer) {
        Order o = orders.lock(dryer.getOrderId()).orElseThrow();
        OrderPolicy p = support.policy(o);
        Instant now = clock.instant();
        boolean extendedAlready = dryer.getActivationCount() >= 2;
        if (!extendedAlready && p.transferExtensionMinutes() > 0) {
            var r = support.reservation(dryer);
            Instant newEnd = r.getEndsAt().plus(Duration.ofMinutes(p.transferExtensionMinutes()));
            if (support.tryResize(dryer, r.getStartsAt(), newEnd)) {
                dryer.setStartDeadlineAt(now.plus(Duration.ofMinutes(p.transferExtensionMinutes())));
                dryer.setStatus(OrderLeg.Status.SCHEDULED);
                activateDryer(o, dryer, now);
                events.record(o.getId(), "TRANSFER_EXTENDED", o.getStatus(), o.getStatus(),
                        Map.of("minutes", p.transferExtensionMinutes()));
                return;
            }
        }
        long forfeit = Money.percentRoundedUp(dryer.getPriceAmount(), p.forfeitPercent(), p.depositRoundingVnd());
        booking.cancelLeg(o, dryer, dryer.getPriceAmount() - Math.min(forfeit, dryer.getPriceAmount()),
                Refund.Reason.DRY_LEG_CANCELLED, "Quá thời gian chuyển đồ sang máy sấy");
    }

    /** Hết thời gian lấy đồ: chặng xong; mọi chặng xong thì đơn hoàn tất. */
    @Transactional
    public void completeUnload(UUID legId) {
        OrderLeg leg = legs.lock(legId).orElse(null);
        if (leg == null || leg.getStatus() != OrderLeg.Status.AWAITING_UNLOAD) return;
        Order o = orders.lock(leg.getOrderId()).orElseThrow();
        leg.setStatus(OrderLeg.Status.DONE);
        leg.setDoneAt(clock.instant());
        legs.flush();
        booking.completeIfDone(o);
    }

    /** Combo: máy sấy bận lúc máy giặt xong; tới giờ của khách thì kích hoạt. */
    @Transactional
    public void activateDryerIfDue(UUID legId) {
        OrderLeg dryer = legs.lock(legId).orElse(null);
        if (dryer == null || dryer.getStatus() != OrderLeg.Status.SCHEDULED || dryer.getStartDeadlineAt() == null
                || dryer.getActivationCount() > 0) return;
        Instant now = clock.instant();
        if (support.reservation(dryer).getStartsAt().isAfter(now)) return;
        activateDryer(orders.lock(dryer.getOrderId()).orElseThrow(), dryer, now);
    }

    // ==================================================================================

    private Optional<OrderLeg> legOf(DeviceEvents.Auth e, UUID authorizationId) {
        return commands.find(authorizationId)
                .filter(c -> c.getDeviceId().equals(e.deviceId()) && "AUTHORIZE_START".equals(c.getCommandType()))
                .map(DeviceCommand::getOrderLegId).flatMap(legs::findById)
                .filter(l -> l.getMachineId().equals(e.machineId()));
    }

    private boolean isLatestAuthorization(OrderLeg leg, UUID authorizationId) {
        return commands.find(authorizationId).map(c -> c.getCreatedAt() == null || leg.getActivatedAt() == null
                || !c.getCreatedAt().isBefore(leg.getActivatedAt().minusSeconds(2))).orElse(false);
    }

    private boolean isComboDryer(OrderLeg leg) {
        return leg.getLegNo() == 2 && leg.getMachineType() == Machine.Type.DRYER;
    }

    private String machineCode(OrderLeg leg) {
        return machines.findById(leg.getMachineId()).map(Machine::getCode).orElse("?");
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
