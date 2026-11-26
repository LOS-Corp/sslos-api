package com.laundry.order;

import com.laundry.audit.AuditService;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.device.DeviceCommand;
import com.laundry.device.DeviceCommandService;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineRepository;
import com.laundry.refund.Refund;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Nhân viên xử lý sự cố của một lượt (§12, §16). Mọi thao tác bắt buộc lý do và ghi nhật ký. */
@Service
@RequiredArgsConstructor
public class StaffOrderService {

    private final OrderRepository orders;
    private final OrderLegRepository legs;
    private final MachineRepository machines;
    private final BookingSupport support;
    private final BookingService booking;
    private final ReassignmentService reassignment;
    private final com.laundry.notification.NotificationService notifications;
    private final DeviceCommandService commands;
    private final OrderEventLog events;
    private final AuditService audit;
    private final Clock clock;

    /**
     * Nhân viên dừng máy của khách. {@code storeFault} (máy kêu lạ, rò nước...): lượt chuyển sang INTERRUPTED để chạy
     * lại miễn phí hoặc hoàn 100%. Không phải lỗi cửa hàng: lượt coi như đã dùng.
     */
    @Transactional
    public Order stop(UUID orderId, int legNo, boolean storeFault, String rawReason) {
        String reason = Reasons.require(rawReason);
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        OrderLeg leg = leg(o, legNo);
        if (leg.getStatus() != OrderLeg.Status.RUNNING) throw new BusinessException(ErrorCode.ORDER_STATE, "Máy không chạy");
        leg.setStatus(storeFault ? OrderLeg.Status.INTERRUPTED : OrderLeg.Status.AWAITING_UNLOAD);
        leg.setInterruptionReason(reason);
        leg.setFinishedAt(clock.instant());
        commands.send(commands.machineTarget(leg.getMachineId()), new DeviceCommandService.Spec("STOP", Map.of(),
                DeviceCommand.Source.STAFF, reason, leg.getId(), null, null));
        audit.user("ORDER_LEG_STOPPED", "order", orderId.toString(), reason, null,
                Map.of("legNo", legNo, "storeFault", storeFault));
        events.record(o.getId(), "LEG_STOPPED_BY_STAFF", o.getStatus(), o.getStatus(),
                Map.of("legNo", legNo, "storeFault", storeFault, "reason", reason));
        return o;
    }

    /**
     * Máy chạy lại được cho lượt bị gián đoạn: cùng loại, đang bán, có kết nối, khác máy vừa hỏng, rảnh từ bây giờ tới
     * hết khung rerun (chờ START + chu trình + lấy đồ + dọn), đúng như {@link #rerun} kiểm tra.
     */
    @Transactional(readOnly = true)
    public java.util.List<SwitchOption> rerunOptions(UUID orderId, int legNo) {
        Order o = orders.findById(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        OrderLeg leg = legs.findByOrderIdOrderByLegNo(o.getId()).stream().filter(l -> l.getLegNo() == legNo).findFirst()
                .orElseThrow(() -> BusinessException.notFound("chặng"));
        if (leg.getStatus() != OrderLeg.Status.INTERRUPTED) return java.util.List.of();
        OrderPolicy p = support.policy(o);
        Instant now = clock.instant();
        java.util.List<Machine> candidates = machines.findByMachineTypeOrderByDisplayOrderAscCodeAsc(leg.getMachineType()).stream()
                .filter(Machine::sellable).filter(m -> !m.getId().equals(leg.getMachineId()))
                .filter(m -> commands.isOnline(m.getId())).toList();
        return candidates.stream().filter(m -> {
            Instant end = now.plus(Duration.ofMinutes(p.startWindowMinutes() + m.getCycleMinutes() + p.unloadMinutes()
                    + p.cleanupMinutes()));
            return support.snapshot(java.util.List.of(m.getId()), now, end).free(m.getId(), now, end, o.getId());
        }).map(m -> new SwitchOption(m.getId(), m.getCode(), m.getDisplayName(), m.getCapacityKg(), m.getCycleMinutes(),
                m.getPriceVnd(), 0)).toList();
    }

    /** Máy hỏng giữa chừng: chạy lại miễn phí trên máy khác cùng loại, ngay bây giờ (§12). */
    @Transactional
    public Order rerun(UUID orderId, int legNo, UUID machineId, String rawReason) {
        String reason = Reasons.require(rawReason);
        support.lockSchedule();
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        OrderLeg leg = leg(o, legNo);
        if (leg.getStatus() != OrderLeg.Status.INTERRUPTED) {
            throw new BusinessException(ErrorCode.ORDER_STATE, "Chỉ chạy lại lượt bị gián đoạn do sự cố");
        }
        Machine m = machines.findById(machineId).filter(x -> x.getMachineType() == leg.getMachineType())
                .filter(Machine::sellable).orElseThrow(() -> new BusinessException(ErrorCode.MACHINE_TYPE_MISMATCH));
        OrderPolicy p = support.policy(o);
        Instant now = clock.instant();
        Instant end = now.plus(Duration.ofMinutes(p.startWindowMinutes() + m.getCycleMinutes() + p.unloadMinutes()
                + p.cleanupMinutes()));
        if (!support.snapshot(java.util.List.of(machineId), now, end).free(machineId, now, end, o.getId())) {
            throw new BusinessException(ErrorCode.MACHINE_UNAVAILABLE);
        }
        support.reservation(leg).setActive(true);
        support.move(leg, machineId, now, end);
        leg.setStatus(OrderLeg.Status.SCHEDULED);
        leg.setCycleMinutes(m.getCycleMinutes());
        leg.setStartedAt(null);
        leg.setFinishedAt(null);
        leg.setStartDeadlineAt(now.plus(Duration.ofMinutes(p.startWindowMinutes())));
        legs.flush();
        ActivationService.Result r = booking.activateCurrentLeg(o, DeviceCommand.Source.STAFF, null, reason);
        audit.user("ORDER_LEG_RERUN", "order", orderId.toString(), reason, null,
                Map.of("legNo", legNo, "machine", m.getCode(), "activated", r.ok()));
        if (!r.ok()) throw new BusinessException(ErrorCode.DEVICE_NOT_CONNECTED, r.problem());
        return o;
    }

    /** Hoàn 100% giá một lượt bị gián đoạn do sự cố cửa hàng (§12). */
    @Transactional
    public Order refundLeg(UUID orderId, int legNo, String rawReason) {
        String reason = Reasons.require(rawReason);
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        OrderLeg leg = leg(o, legNo);
        if (leg.getStatus() != OrderLeg.Status.INTERRUPTED && !leg.notStarted()) {
            throw new BusinessException(ErrorCode.ORDER_STATE, "Chỉ hoàn lượt bị gián đoạn hoặc chưa chạy");
        }
        booking.cancelLeg(o, leg, leg.getPriceAmount(), Refund.Reason.STORE_FAULT, reason);
        audit.user("ORDER_LEG_REFUNDED", "order", orderId.toString(), reason, null,
                Map.of("legNo", legNo, "amount", leg.getPriceAmount()));
        return o;
    }

    /** Máy đổi được cho một lượt chưa chạy: cùng loại, đang bán, có kết nối, rảnh suốt khoảng giữ máy của lượt. */
    public record SwitchOption(UUID machineId, String code, String displayName, java.math.BigDecimal capacityKg,
                               int cycleMinutes, long priceVnd, long refundIfSwitched) {
    }

    @Transactional(readOnly = true)
    public java.util.List<SwitchOption> switchOptions(UUID orderId, int legNo) {
        Order o = orders.findById(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        OrderLeg leg = legs.findByOrderIdOrderByLegNo(o.getId()).stream().filter(l -> l.getLegNo() == legNo).findFirst()
                .orElseThrow(() -> BusinessException.notFound("chặng"));
        if (!leg.notStarted() || !o.isOpen()) return java.util.List.of();
        var r = support.reservation(leg);
        Instant now = clock.instant();
        Instant start = r.getStartsAt().isBefore(now) ? now : r.getStartsAt();
        Instant end = r.getEndsAt().isAfter(start) ? r.getEndsAt() : start.plusSeconds(60);
        java.util.List<Machine> candidates = machines.findByMachineTypeOrderByDisplayOrderAscCodeAsc(leg.getMachineType()).stream()
                .filter(Machine::sellable).filter(m -> !m.getId().equals(leg.getMachineId()))
                .filter(m -> commands.isOnline(m.getId())).toList();
        ScheduleSnapshot snap = support.snapshot(candidates.stream().map(Machine::getId).toList(), start, end);
        return candidates.stream().filter(m -> snap.free(m.getId(), start, end, o.getId()))
                .map(m -> new SwitchOption(m.getId(), m.getCode(), m.getDisplayName(), m.getCapacityKg(),
                        m.getCycleMinutes(), m.getPriceVnd(), Math.max(0, leg.getPriceAmount() - m.getPriceVnd())))
                .toList();
    }

    /**
     * Chuyển một lượt chưa chạy sang máy khác (máy có vấn đề, đến sớm...). Máy mới rẻ hơn thì hoàn phần chênh vào ví;
     * đắt hơn khách không trả thêm.
     */
    @Transactional
    public Order switchMachine(UUID orderId, int legNo, UUID machineId, String rawReason) {
        String reason = Reasons.require(rawReason);
        support.lockSchedule();
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        if (!o.isOpen()) throw new BusinessException(ErrorCode.ORDER_STATE, "Đơn đã kết thúc");
        OrderLeg leg = leg(o, legNo);
        if (!leg.notStarted()) throw new BusinessException(ErrorCode.ORDER_STATE, "Lượt đã chạy");
        if (leg.getMachineId().equals(machineId)) throw new BusinessException(ErrorCode.ORDER_STATE, "Lượt đang ở máy này");
        Machine m = machines.findById(machineId).filter(x -> x.getMachineType() == leg.getMachineType())
                .filter(Machine::sellable).orElseThrow(() -> new BusinessException(ErrorCode.MACHINE_TYPE_MISMATCH));
        var r = support.reservation(leg);
        Instant now = clock.instant();
        Instant start = r.getStartsAt().isBefore(now) ? now : r.getStartsAt();
        if (!support.snapshot(java.util.List.of(machineId), start, r.getEndsAt()).free(machineId, start, r.getEndsAt(), o.getId())) {
            throw new BusinessException(ErrorCode.MACHINE_UNAVAILABLE);
        }
        String from = machines.findById(leg.getMachineId()).map(Machine::getCode).orElse("?");
        support.move(leg, machineId, start, r.getEndsAt());
        long refund = reassignment.applyMachine(o, leg, m);
        orders.saveAndFlush(o);
        audit.user("ORDER_LEG_SWITCHED", "order", orderId.toString(), reason, Map.of("machine", from),
                Map.of("machine", m.getCode(), "refund", refund));
        events.record(o.getId(), "MACHINE_SWITCHED", o.getStatus(), o.getStatus(),
                Map.of("legNo", legNo, "from", from, "to", m.getCode(), "refund", refund, "reason", reason));
        notifications.order(o, com.laundry.notification.Notification.Type.MACHINE_REASSIGNED, "Đổi máy",
                "Đơn " + o.getCode() + " chuyển từ máy " + from + " sang máy " + m.getCode()
                        + (refund > 0 ? "; hoàn chênh lệch " + com.laundry.common.Money.format(refund) : "") + ".", null);
        return o;
    }

    private OrderLeg leg(Order o, int legNo) {
        return legs.findByOrderIdOrderByLegNo(o.getId()).stream().filter(l -> l.getLegNo() == legNo).findFirst()
                .flatMap(l -> legs.lock(l.getId())).orElseThrow(() -> BusinessException.notFound("chặng"));
    }
}
