package com.laundry.order;

import com.laundry.device.DeviceCommand;
import com.laundry.device.DeviceCommandService;
import com.laundry.device.link.MqttProperties;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineRepository;
import com.laundry.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kích hoạt máy cho một chặng (§8): gửi AUTHORIZE_START; khách có {@code windowMinutes} phút để bấm START.
 * authorizationId = commandId của lệnh, để START_REQUEST / STARTED lần ngược được về chặng.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActivationService {

    /** Máy giặt GA25: chương trình mặc định (vắt 2 phút, đảo 8 s nghỉ 10 s). */
    static final int SPIN_SECONDS = 120, DRUM_RUN_SECONDS = 8, DRUM_PAUSE_SECONDS = 10;

    private final DeviceCommandService commands;
    private final MachineRepository machines;
    private final BookingSupport support;
    private final OrderEventLog events;
    private final NotificationService notifications;
    private final MqttProperties mqtt;
    private final Clock clock;

    public record Result(boolean ok, String machineCode, Instant expiresAt, String problem) {
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Result activate(Order order, OrderLeg leg, DeviceCommand.Source source, UUID kioskId, String reason,
                           int windowMinutes) {
        Instant now = clock.instant();
        Machine m = machines.findById(leg.getMachineId()).orElseThrow();
        if (m.getAdminStatus() != Machine.AdminStatus.ACTIVE) {
            return new Result(false, m.getCode(), null, "Máy " + m.getCode() + " đang " + m.getAdminStatus());
        }
        // Check-in sớm: máy chạy sớm nhưng lượt không được kéo dài (§6.2): kéo đầu khoảng giữ về hiện tại.
        if (now.isBefore(support.reservation(leg).getStartsAt())
                && !support.tryResize(leg, now, support.reservation(leg).getEndsAt())) {
            return new Result(false, m.getCode(), null, "Máy " + m.getCode() + " đang bận");
        }
        UUID authorizationId = UUID.randomUUID();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("authorizationId", authorizationId.toString());
        fields.put("bookingId", order.getId().toString());
        fields.put("expiresInSeconds", windowMinutes * 60);
        int cycleSeconds = (leg.getCycleMinutes() + leg.getExtraMinutes()) * 60;
        if (m.getMachineType() == Machine.Type.DRYER) {
            fields.put("cycleSeconds", cycleSeconds);
        } else {
            int spin = Math.min(SPIN_SECONDS, Math.max(0, cycleSeconds - 60));
            fields.put("program", Map.of("code", "WASH_STD", "washSeconds", Math.max(60, cycleSeconds - spin),
                    "spinSeconds", spin, "drumRunSeconds", DRUM_RUN_SECONDS, "drumPauseSeconds", DRUM_PAUSE_SECONDS,
                    "timeDivisor", mqtt.effectiveTimeDivisor()));
        }
        DeviceCommandService.Target target;
        try {
            target = commands.machineTarget(m.getId());
        } catch (RuntimeException e) {
            return new Result(false, m.getCode(), null, "Máy " + m.getCode() + " chưa gắn bo mạch");
        }
        boolean sent = commands.trySend(target, new DeviceCommandService.Spec(authorizationId, "AUTHORIZE_START", fields,
                source, reason, leg.getId(), null, kioskId));
        if (!sent) return new Result(false, m.getCode(), null, "Máy " + m.getCode() + " đang mất kết nối");

        Instant expires = now.plusSeconds(windowMinutes * 60L);
        leg.setStatus(OrderLeg.Status.ACTIVATED);
        leg.setActivatedAt(now);
        leg.setActivationExpiresAt(expires);
        leg.setActivationCount(leg.getActivationCount() + 1);
        Order.Status before = order.getStatus();
        if (order.getStatus() == Order.Status.CONFIRMED) order.setStatus(Order.Status.IN_PROGRESS);
        if (order.getCheckedInAt() == null) order.setCheckedInAt(now);
        notifications.cancelReminders(order.getId());
        events.record(order.getId(), "LEG_ACTIVATED", before, order.getStatus(),
                Map.of("legNo", leg.getLegNo(), "machine", m.getCode(), "expiresAt", expires.toString(),
                        "source", source.name(), "count", leg.getActivationCount()));
        return new Result(true, m.getCode(), expires, null);
    }

    /** Thu hồi quyền bấm START trên máy (best effort). */
    public void revoke(OrderLeg leg, UUID authorizationId, String reason) {
        try {
            DeviceCommandService.Target target = commands.machineTarget(leg.getMachineId());
            commands.trySend(target, DeviceCommandService.Spec.system("REVOKE_START",
                    Map.of("authorizationId", String.valueOf(authorizationId), "reason", reason)));
        } catch (RuntimeException e) {
            log.debug("revoke on {} skipped: {}", leg.getMachineId(), e.getMessage());
        }
    }
}
