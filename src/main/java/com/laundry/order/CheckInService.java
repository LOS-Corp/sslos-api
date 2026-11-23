package com.laundry.order;

import com.laundry.audit.AuditService;
import com.laundry.common.Phones;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.device.DeviceCommand;
import com.laundry.device.DeviceCommandService;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineRepository;
import com.laundry.payment.PaymentIntent;
import com.laundry.payment.PaymentService;
import com.laundry.store.StoreSettings;
import com.laundry.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Kiosk là nơi duy nhất check-in và kích hoạt máy (§1, §8). QR quét được nhiều lần trong thời gian check-in, tiền
 * chỉ thu một lần (§7.4).
 */
@Service
@RequiredArgsConstructor
public class CheckInService {

    public enum Outcome {
        /** Kiosk hiện "Mời ra máy số X bấm START trong N phút". */
        ACTIVATED,
        /** Đã kích hoạt, vẫn còn thời hạn bấm START. */
        ALREADY_ACTIVATED,
        /** Còn phải trả: kiosk hiện QR của {@code payment}. */
        PAYMENT_REQUIRED,
        TOO_EARLY,
        /** Đến sớm, máy đang bận: {@code alternatives} là máy cùng loại, cùng giá đang rảnh. */
        MACHINE_BUSY,
        CHECKIN_CLOSED,
        /** Máy đang chạy / đang chờ lấy đồ. */
        RUNNING,
        /** Máy sấy (combo) sẽ tự kích hoạt khi máy giặt xong. */
        WAITING_WASHER,
        /** Máy được gán hỏng, không có máy thay thế: đã hoàn 100%. */
        REFUNDED,
        /** Không gửi được lệnh xuống máy: gặp nhân viên. */
        MACHINE_PROBLEM
    }

    public record Alternative(UUID machineId, String code, long priceVnd) {
    }

    public record Result(Outcome outcome, Order order, String machineCode, Instant until, PaymentIntent payment,
                         List<Alternative> alternatives, String message) {
    }

    private final OrderRepository orders;
    private final MachineRepository machines;
    private final UserRepository users;
    private final BookingSupport support;
    private final ActivationService activation;
    private final ReassignmentService reassignment;
    private final PaymentService payments;
    private final DeviceCommandService devices;
    private final AuditService audit;
    private final QrCodes qr;
    private final Clock clock;

    /** Tìm đơn theo QR, hoặc mã đơn + 4 số cuối điện thoại (§8.1, mất điện thoại). */
    public Order find(String qrToken, String code, String phoneLast4) {
        if (qrToken != null && !qrToken.isBlank()) {
            return orders.findByQrTokenHash(QrCodes.hash(qrToken))
                    .orElseThrow(() -> BusinessException.notFound("đơn của mã QR này"));
        }
        if (code == null || phoneLast4 == null || !phoneLast4.matches("\\d{4}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Quét QR, hoặc nhập mã đơn và 4 số cuối điện thoại");
        }
        Order o = orders.findByCode(code.trim().toUpperCase()).orElseThrow(() -> BusinessException.notFound("đơn"));
        String phone = o.getGuestPhone() != null ? o.getGuestPhone()
                : o.getCustomerId() == null ? null : users.findById(o.getCustomerId()).map(u -> u.getPhone()).orElse(null);
        if (phone == null || !Phones.lastFour(phone).equals(phoneLast4)) throw BusinessException.notFound("đơn");
        return o;
    }

    @Transactional
    public Result scan(UUID kioskId, String qrToken, String code, String phoneLast4) {
        support.lockSchedule();
        Order o = orders.lock(find(qrToken, code, phoneLast4).getId()).orElseThrow();
        return checkIn(o, DeviceCommand.Source.KIOSK, kioskId, null);
    }

    /** Nhân viên kích hoạt thủ công khi kiosk lỗi (§12), bắt buộc lý do. */
    @Transactional
    public Result staffActivate(UUID orderId, String rawReason) {
        String reason = Reasons.require(rawReason);
        support.lockSchedule();
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        audit.user("MANUAL_ACTIVATION", "order", orderId.toString(), reason, null, Map.of("code", o.getCode()));
        return checkIn(o, DeviceCommand.Source.STAFF, null, reason);
    }

    /** Đến sớm mà máy bận: chuyển sang máy cùng loại, cùng giá đang rảnh (*), rồi kích hoạt. */
    @Transactional
    public Result switchMachine(UUID kioskId, UUID orderId, UUID machineId) {
        support.lockSchedule();
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        OrderLeg leg = currentLeg(o).orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE));
        Alternative alt = alternatives(o, leg).stream().filter(a -> a.machineId().equals(machineId)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.MACHINE_UNAVAILABLE));
        Instant now = clock.instant();
        support.move(leg, alt.machineId(), now, support.reservation(leg).getEndsAt());
        return checkIn(o, DeviceCommand.Source.KIOSK, kioskId, null);
    }

    private Result checkIn(Order o, DeviceCommand.Source source, UUID kioskId, String reason) {
        Instant now = clock.instant();
        OrderPolicy p = support.policy(o);
        switch (o.getStatus()) {
            case HOLD -> throw new BusinessException(ErrorCode.ORDER_STATE, "Đơn chưa thanh toán");
            case COMPLETED, CANCELLED, NO_SHOW, EXPIRED ->
                    throw new BusinessException(ErrorCode.ORDER_STATE, "Mã QR đã hết hiệu lực");
            default -> {
            }
        }
        List<OrderLeg> legs = support.legs(o.getId());
        Optional<OrderLeg> current = currentLeg(o);
        if (current.isEmpty()) {
            OrderLeg running = legs.stream().filter(l -> l.getStatus() == OrderLeg.Status.RUNNING
                    || l.getStatus() == OrderLeg.Status.AWAITING_UNLOAD).findFirst().orElse(null);
            if (running != null) {
                return new Result(Outcome.RUNNING, o, code(running), running.getExpectedEndAt(), null, List.of(),
                        running.getStatus() == OrderLeg.Status.RUNNING ? "Máy đang chạy" : "Máy đã xong, mời lấy đồ");
            }
            throw new BusinessException(ErrorCode.ORDER_STATE, "Không còn chặng nào chờ kích hoạt");
        }
        OrderLeg leg = current.get();
        boolean dryerOfCombo = o.getService() == Order.Service.WASH_DRY && leg.getLegNo() == 2;
        if (dryerOfCombo && leg.getStartDeadlineAt() == null) {
            return new Result(Outcome.WAITING_WASHER, o, code(leg), null, null, List.of(),
                    "Máy sấy sẽ tự kích hoạt khi máy giặt xong");
        }
        if (!dryerOfCombo && now.isBefore(o.getCheckinOpensAt())) {
            return new Result(Outcome.TOO_EARLY, o, code(leg), o.getCheckinOpensAt(), null, List.of(),
                    "Chưa đến giờ, mời quay lại lúc " + hhmm(o.getCheckinOpensAt()));
        }
        Instant deadline = leg.getStartDeadlineAt() != null ? leg.getStartDeadlineAt() : o.getCheckinDeadlineAt();
        if (now.isAfter(deadline)) {
            return new Result(Outcome.CHECKIN_CLOSED, o, code(leg), deadline, null, List.of(),
                    "Đã hết thời gian check-in, mời gặp nhân viên");
        }
        if (leg.getStatus() == OrderLeg.Status.ACTIVATED && leg.getActivationExpiresAt() != null
                && leg.getActivationExpiresAt().isAfter(now)) {
            return new Result(Outcome.ALREADY_ACTIVATED, o, code(leg), leg.getActivationExpiresAt(), null, List.of(),
                    "Mời ra máy số " + code(leg) + " bấm START");
        }
        // Máy được gán hỏng / mất kết nối / bảo trì: chuyển máy hoặc hoàn 100% (§12).
        Machine m = machines.findById(leg.getMachineId()).orElseThrow();
        if (m.getAdminStatus() != Machine.AdminStatus.ACTIVE || !devices.isOnline(m.getId())) {
            if (!reassignment.reassign(o, leg, "Máy " + m.getCode() + " không dùng được lúc check-in")) {
                return new Result(Outcome.REFUNDED, o, m.getCode(), null, null, List.of(),
                        "Máy gặp sự cố và không có máy thay thế; đã hoàn 100% số tiền");
            }
        }
        // Đến sớm: chỉ kích hoạt khi máy đang rảnh tới giờ của khách.
        if (now.isBefore(support.reservation(leg).getStartsAt())) {
            ScheduleSnapshot snap = support.snapshot(List.of(leg.getMachineId()), now, support.reservation(leg).getStartsAt());
            if (!snap.free(leg.getMachineId(), now, support.reservation(leg).getStartsAt(), o.getId())) {
                return new Result(Outcome.MACHINE_BUSY, o, code(leg), support.reservation(leg).getStartsAt(), null,
                        alternatives(o, leg), "Máy số " + code(leg) + " đang bận, rảnh lúc "
                        + hhmm(support.reservation(leg).getStartsAt()));
            }
        }
        if (!o.fullyPaid()) {
            Instant expires = min(now.plus(Duration.ofMinutes(StoreSettings.PAYMENT_HOLD_MINUTES)), deadline);
            if (o.getCheckedInAt() == null) o.setCheckedInAt(now);
            PaymentIntent intent = payments.create(o, PaymentIntent.Purpose.BALANCE, o.remainingAmount(), false, expires);
            return new Result(Outcome.PAYMENT_REQUIRED, o, code(leg), expires, intent, List.of(),
                    "Còn phải trả, quét QR để thanh toán");
        }
        int window = dryerOfCombo ? (int) Math.max(1, Duration.between(now, deadline).toMinutes()) : p.startWindowMinutes();
        ActivationService.Result r = activation.activate(o, leg, source, kioskId, reason, window);
        if (!r.ok()) return new Result(Outcome.MACHINE_PROBLEM, o, r.machineCode(), null, null, List.of(), r.problem());
        return new Result(Outcome.ACTIVATED, o, r.machineCode(), r.expiresAt(), null, List.of(),
                "Mời ra máy số " + r.machineCode() + " bấm START trong " + window + " phút");
    }

    private Optional<OrderLeg> currentLeg(Order o) {
        return support.legs(o.getId()).stream().filter(OrderLeg::notStarted).findFirst()
                .filter(l -> support.legs(o.getId()).stream().noneMatch(x -> x.getLegNo() < l.getLegNo()
                        && (x.getStatus() == OrderLeg.Status.RUNNING || x.notStarted())));
    }

    /** Máy cùng loại, cùng giá, đang online, rảnh từ bây giờ tới hết khoảng giữ máy của khách. */
    private List<Alternative> alternatives(Order o, OrderLeg leg) {
        Instant now = clock.instant();
        Instant end = support.reservation(leg).getEndsAt();
        List<Machine> candidates = machines.findByMachineTypeOrderByDisplayOrderAscCodeAsc(leg.getMachineType()).stream()
                .filter(Machine::sellable).filter(m -> !m.getId().equals(leg.getMachineId()))
                .filter(m -> m.getPriceVnd() == leg.getPriceAmount())
                .filter(m -> m.getCapacityKg().compareTo(leg.getCapacityKg()) >= 0)
                .filter(m -> devices.isOnline(m.getId())).toList();
        ScheduleSnapshot snap = support.snapshot(candidates.stream().map(Machine::getId).toList(), now, end);
        return candidates.stream().filter(m -> snap.free(m.getId(), now, end, o.getId()))
                .map(m -> new Alternative(m.getId(), m.getCode(), m.getPriceVnd())).toList();
    }

    private String code(OrderLeg leg) {
        return machines.findById(leg.getMachineId()).map(Machine::getCode).orElse("?");
    }

    private static String hhmm(Instant t) {
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(t);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
