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

    /** Chuyển một lượt chưa chạy sang máy khác (đến sớm, máy có vấn đề...). */
    @Transactional
    public Order switchMachine(UUID orderId, int legNo, UUID machineId, String rawReason) {
        String reason = Reasons.require(rawReason);
        support.lockSchedule();
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        OrderLeg leg = leg(o, legNo);
        if (!leg.notStarted()) throw new BusinessException(ErrorCode.ORDER_STATE, "Lượt đã chạy");
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
        if (leg.getStatus() == OrderLeg.Status.ACTIVATED) leg.setStatus(OrderLeg.Status.SCHEDULED);
        audit.user("ORDER_LEG_SWITCHED", "order", orderId.toString(), reason, Map.of("machine", from),
                Map.of("machine", m.getCode()));
        events.record(o.getId(), "MACHINE_SWITCHED", o.getStatus(), o.getStatus(),
                Map.of("legNo", legNo, "from", from, "to", m.getCode()));
        return o;
    }

    private OrderLeg leg(Order o, int legNo) {
        return legs.findByOrderIdOrderByLegNo(o.getId()).stream().filter(l -> l.getLegNo() == legNo).findFirst()
                .flatMap(l -> legs.lock(l.getId())).orElseThrow(() -> BusinessException.notFound("chặng"));
    }
}
