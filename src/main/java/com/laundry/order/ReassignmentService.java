package com.laundry.order;

import com.laundry.common.Money;
import com.laundry.device.DeviceCommandService;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineAvailabilityChanged;
import com.laundry.machine.MachineRepository;
import com.laundry.notification.Notification;
import com.laundry.notification.NotificationService;
import com.laundry.refund.Refund;
import com.laundry.refund.RefundService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Sự cố từ phía cửa hàng (§12): khách không mất tiền, không tính vi phạm.
 * Máy được gán bị lỗi / mất kết nối / bảo trì trước giờ khách dùng: tự chuyển sang máy cùng loại, sức chứa không
 * nhỏ hơn, đang rảnh trong khoảng giữ máy; máy mới rẻ hơn thì hoàn phần chênh; không có máy thì hoàn 100%.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReassignmentService {

    private final OrderRepository orders;
    private final OrderLegRepository legs;
    private final MachineRepository machines;
    private final BookingSupport support;
    private final BookingService booking;
    private final RefundService refunds;
    private final NotificationService notifications;
    private final DeviceCommandService devices;
    private final OrderEventLog events;
    private final Clock clock;

    @EventListener
    @Transactional
    public void onMachineUnavailable(MachineAvailabilityChanged e) {
        if (e.available()) return;
        support.lockSchedule();
        for (OrderLeg leg : legs.onMachine(e.machineId(), List.of(OrderLeg.Status.SCHEDULED, OrderLeg.Status.ACTIVATED))) {
            Order o = orders.lock(leg.getOrderId()).orElseThrow();
            if (!o.isOpen()) continue;
            reassign(o, leg, e.reason());
        }
    }

    /**
     * Chuyển một chặng chưa chạy sang máy khác. Trả false khi không có máy thay thế (đơn đã được hủy, hoàn 100%).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean reassign(Order o, OrderLeg leg, String reason) {
        Instant now = clock.instant();
        var r = support.reservation(leg);
        Instant start = r.getStartsAt().isBefore(now) ? now : r.getStartsAt();
        Instant end = r.getEndsAt().isAfter(start) ? r.getEndsAt() : start.plusSeconds(60);
        List<Machine> candidates = machines.findByMachineTypeOrderByDisplayOrderAscCodeAsc(leg.getMachineType()).stream()
                .filter(Machine::sellable).filter(m -> !m.getId().equals(leg.getMachineId()))
                .filter(m -> m.getCapacityKg().compareTo(leg.getCapacityKg()) >= 0)
                .filter(m -> devices.isOnline(m.getId())).toList();
        ScheduleSnapshot snap = support.snapshot(candidates.stream().map(Machine::getId).toList(), start, end);
        Machine target = candidates.stream().filter(m -> snap.free(m.getId(), start, end, o.getId()))
                // Ưu tiên máy cùng giá, rồi máy đắt hơn (khách không trả thêm), cuối cùng máy rẻ hơn (hoàn chênh).
                .min(Comparator.comparingInt((Machine m) -> m.getPriceVnd() == leg.getPriceAmount() ? 0
                        : m.getPriceVnd() > leg.getPriceAmount() ? 1 : 2)
                        .thenComparingLong(m -> Math.abs(m.getPriceVnd() - leg.getPriceAmount())))
                .orElse(null);
        String fromCode = machines.findById(leg.getMachineId()).map(Machine::getCode).orElse("?");
        if (target == null) {
            log.warn("[REASSIGN] {} leg {}: no replacement for {}; refund", o.getCode(), leg.getLegNo(), fromCode);
            boolean otherLegUsed = legs.findByOrderIdOrderByLegNo(o.getId()).stream()
                    .anyMatch(l -> !l.getId().equals(leg.getId()) && !l.notStarted() && l.getStatus() != OrderLeg.Status.CANCELLED);
            if (otherLegUsed) {
                // Combo: đã giặt xong, máy sấy hỏng -> hoàn 100% giá chặng sấy.
                booking.cancelLeg(o, leg, leg.getPriceAmount(), Refund.Reason.STORE_FAULT, reason + "; không có máy thay thế");
            } else {
                booking.cancelForStoreFault(o, reason + "; không có máy thay thế");
            }
            return false;
        }
        support.move(leg, target.getId(), start, end);
        long diff = leg.getPriceAmount() - target.getPriceVnd();
        if (diff > 0) {
            leg.setPriceAmount(target.getPriceVnd());
            o.setTotalAmount(o.getTotalAmount() - diff);
            o.setDepositAmount(Math.min(o.getDepositAmount(), o.getTotalAmount()));
            long refundable = o.getPaidAmount() - o.getRefundedAmount() - o.getForfeitedAmount() - o.getTotalAmount();
            if (refundable > 0) refunds.auto(o, Math.min(diff, refundable), Refund.Reason.MACHINE_DOWNGRADE,
                    "Chuyển sang máy rẻ hơn " + target.getCode());
        }
        leg.setCycleMinutes(target.getCycleMinutes());
        leg.setCapacityKg(target.getCapacityKg());
        if (leg.getStatus() == OrderLeg.Status.ACTIVATED) leg.setStatus(OrderLeg.Status.SCHEDULED);
        notifications.order(o, Notification.Type.MACHINE_REASSIGNED, "Đổi máy",
                "Đơn " + o.getCode() + " chuyển từ máy " + fromCode + " sang máy " + target.getCode()
                        + (diff > 0 ? "; hoàn chênh lệch " + Money.format(diff) : "") + ".", null);
        events.record(o.getId(), "MACHINE_REASSIGNED", o.getStatus(), o.getStatus(),
                Map.of("legNo", leg.getLegNo(), "from", fromCode, "to", target.getCode(), "reason", reason,
                        "refund", Math.max(0, diff)));
        return true;
    }
}
