package com.laundry.order;

import com.laundry.audit.AuditService;
import com.laundry.common.Money;
import com.laundry.common.Phones;
import com.laundry.common.Reasons;
import com.laundry.common.Tokens;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.device.DeviceCommand;
import com.laundry.machine.Machine;
import com.laundry.machine.MachineRepository;
import com.laundry.machine.MachineReservation;
import com.laundry.notification.Notification;
import com.laundry.notification.NotificationService;
import com.laundry.payment.PaymentEvents;
import com.laundry.payment.PaymentIntent;
import com.laundry.payment.PaymentService;
import com.laundry.refund.Refund;
import com.laundry.refund.RefundService;
import com.laundry.store.StoreService;
import com.laundry.store.StoreSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Vòng đời đơn tự phục vụ (§5, §7, §8.2, §11). */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final OrderRepository orders;
    private final OrderLegRepository legRepository;
    private final MachineRepository machines;
    private final BookingSupport support;
    private final AvailabilityService availability;
    private final ActivationService activation;
    private final StoreService store;
    private final PaymentService payments;
    private final RefundService refunds;
    private final NotificationService notifications;
    private final OrderEventLog events;
    private final AuditService audit;
    private final QrCodes qr;
    private final Clock clock;

    public enum PayOption { DEPOSIT, FULL, BALANCE }

    // ==================================================================================
    // Đặt trước trên app (§7)
    // ==================================================================================

    @Transactional
    public Order createAdvance(UUID customerId, Order.Service service, Instant startAt, UUID washerId, UUID dryerId) {
        support.lockSchedule();
        StoreSettings s = store.settings();
        store.requireReady();
        requireServiceEnabled(s, service);
        Instant start = startAt.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        String timeProblem = availability.timeProblem(s, start);
        if (timeProblem != null) throw new BusinessException(ErrorCode.ADVANCE_LIMIT, timeProblem);

        Instant now = clock.instant();
        boolean penalty = ViolationPolicy.penaltyActive(
                orders.violationTimes(customerId, now.minus(Duration.ofDays(s.getViolationWindowDays() + s.getViolationPenaltyDays()))),
                now, s.getViolationThreshold(), s.getViolationWindowDays(), s.getViolationPenaltyDays());
        OrderPolicy p = OrderPolicy.from(s, penalty ? 100 : s.getDepositPercent());

        AvailabilityService.AdvanceResult avail = availability.advance(service, start, washerId, null);
        List<Machine> chosen = pickMachines(service, washerId, dryerId, avail);

        Order o = newOrder(Order.Channel.APP, service, p, start);
        o.setCustomerId(customerId);
        o.setHoldExpiresAt(now.plus(Duration.ofMinutes(StoreSettings.PAYMENT_HOLD_MINUTES)));
        o.setCheckinOpensAt(start.minus(Duration.ofMinutes(p.earlyCheckinMinutes())));
        o.setCheckinDeadlineAt(start.plus(Duration.ofMinutes(p.graceMinutes())));
        long total = chosen.stream().mapToLong(Machine::getPriceVnd).sum();
        o.setTotalAmount(total);
        o.setDepositAmount(Money.percentRoundedUp(total, p.depositPercent(), p.depositRoundingVnd()));
        orders.saveAndFlush(o);
        createLegs(o, chosen, start, p);
        events.record(o.getId(), "CREATED", null, Order.Status.HOLD, Map.of("channel", "APP", "service", service,
                "start", start.toString(), "penalty", penalty));
        return o;
    }

    /** Khách trả tiền giữ chỗ / trả đủ / trả nốt phần còn lại trên app (§7.3, §7.5). */
    @Transactional
    public PaymentIntent pay(UUID customerId, UUID orderId, PayOption option, boolean useWallet) {
        Order o = lockOwned(customerId, orderId);
        Instant now = clock.instant();
        long amount;
        PaymentIntent.Purpose purpose;
        Instant expires;
        if (o.getStatus() == Order.Status.HOLD) {
            if (now.isAfter(o.getHoldExpiresAt())) throw new BusinessException(ErrorCode.PAYMENT_EXPIRED, "Hết thời gian giữ chỗ");
            purpose = option == PayOption.DEPOSIT ? PaymentIntent.Purpose.DEPOSIT : PaymentIntent.Purpose.FULL;
            amount = (purpose == PaymentIntent.Purpose.DEPOSIT ? o.getDepositAmount() : o.getTotalAmount()) - o.getPaidAmount();
            expires = o.getHoldExpiresAt();
        } else if (o.getStatus() == Order.Status.CONFIRMED || o.getStatus() == Order.Status.IN_PROGRESS) {
            purpose = PaymentIntent.Purpose.BALANCE;
            amount = o.remainingAmount();
            expires = min(now.plus(Duration.ofMinutes(StoreSettings.PAYMENT_HOLD_MINUTES)), o.getCheckinDeadlineAt());
        } else {
            throw new BusinessException(ErrorCode.ORDER_STATE);
        }
        if (amount <= 0) throw new BusinessException(ErrorCode.ORDER_STATE, "Đơn đã được trả đủ");
        return payments.create(o, purpose, amount, useWallet, expires);
    }

    // ==================================================================================
    // Đặt ngay tại kiosk (§8.2)
    // ==================================================================================

    public record WalkIn(Order order, PaymentIntent intent) {
    }

    @Transactional
    public WalkIn createWalkIn(UUID kioskId, Order.Service service, UUID washerId, UUID dryerId, String rawPhone) {
        support.lockSchedule();
        StoreSettings s = store.settings();
        store.requireReady();
        requireServiceEnabled(s, service);
        Instant now = clock.instant();
        OrderPolicy p = OrderPolicy.from(s, 100);
        List<AvailabilityService.Option> options = availability.walkIn(service);
        List<Machine> chosen = new ArrayList<>();
        Instant washerAt = null;
        for (Machine.Type type : List.of(Machine.Type.WASHER, Machine.Type.DRYER)) {
            if (!AvailabilityService.needs(service, type)) continue;
            UUID wanted = type == Machine.Type.WASHER ? washerId : dryerId;
            if (wanted == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Chọn " + label(type));
            AvailabilityService.Option opt = options.stream().filter(x -> x.machineId().equals(wanted)).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.MACHINE_UNAVAILABLE));
            availability.requireBookable(opt);
            chosen.add(machines.findById(wanted).orElseThrow());
            if (type == Machine.Type.WASHER || service == Order.Service.DRY) washerAt = opt.availableAt();
        }
        Instant start = washerAt == null ? now : washerAt;
        boolean immediate = !start.isAfter(now.plusSeconds(30));
        if (chosen.size() > 1) {
            Instant worst = immediate ? now.plus(Duration.ofMinutes(StoreSettings.PAYMENT_HOLD_MINUTES)) : start;
            Timeline.Window dw = Timeline.dryerAfterWash(worst, chosen.get(0).getCycleMinutes(),
                    chosen.get(1).getCycleMinutes(), p);
            if (!store.calendar().fitsOpen(dw.start(), dw.unloadEnd())) {
                throw new BusinessException(ErrorCode.STORE_CLOSED, "Không kịp sấy xong trước giờ đóng cửa");
            }
        }
        Order o = newOrder(Order.Channel.KIOSK, service, p, immediate ? now : start);
        o.setKioskId(kioskId);
        o.setGuestPhone(rawPhone == null || rawPhone.isBlank() ? null : Phones.normalize(rawPhone));
        o.setHoldExpiresAt(now.plus(Duration.ofMinutes(StoreSettings.PAYMENT_HOLD_MINUTES)));
        // T thật của đơn rảnh ngay = lúc trả xong; trong lúc chờ trả, tính sẵn trường hợp trả muộn nhất.
        Instant worstT = immediate ? o.getHoldExpiresAt() : start;
        o.setCheckinOpensAt(immediate ? now : start);
        o.setCheckinDeadlineAt(worstT.plus(Duration.ofMinutes(p.graceMinutes())));
        long total = chosen.stream().mapToLong(Machine::getPriceVnd).sum();
        o.setTotalAmount(total);
        o.setDepositAmount(total);
        orders.saveAndFlush(o);
        createLegsWalkIn(o, chosen, immediate ? now : start, worstT, p);
        events.record(o.getId(), "CREATED", null, Order.Status.HOLD, Map.of("channel", "KIOSK", "service", service,
                "immediate", immediate, "start", start.toString()));
        PaymentIntent intent = payments.create(o, PaymentIntent.Purpose.FULL, total, false, o.getHoldExpiresAt());
        return new WalkIn(o, intent);
    }

    // ==================================================================================
    // Tiền về
    // ==================================================================================

    @EventListener
    public void onIntentPaid(PaymentEvents.IntentPaid e) {
        PaymentIntent.Purpose purpose = e.purpose();
        if (purpose == PaymentIntent.Purpose.DRYER_EXTENSION || purpose == PaymentIntent.Purpose.LAUNDRY_MINIMUM
                || purpose == PaymentIntent.Purpose.LAUNDRY_BALANCE || purpose == PaymentIntent.Purpose.STORAGE_FEE) return;
        Order o = orders.lock(e.orderId()).orElseThrow();
        if (o.getService() == Order.Service.LAUNDRY) return;
        Instant now = clock.instant();
        if (o.getStatus() == Order.Status.HOLD && o.getPaidAmount() >= o.getDepositAmount()) {
            confirm(o, now);
        } else if (purpose == PaymentIntent.Purpose.BALANCE && o.fullyPaid() && o.isOpen()) {
            events.record(o.getId(), "PAID_IN_FULL", o.getStatus(), o.getStatus(), Map.of("paid", o.getPaidAmount()));
            PaymentIntent intent = payments.intent(e.intentId());
            // Trả nốt tại kiosk lúc check-in: tiền về đủ thì kích hoạt máy luôn (§8.1 bước 4).
            if (intent.getKioskId() != null && !now.isAfter(o.getCheckinDeadlineAt())) {
                safeActivate(o, DeviceCommand.Source.KIOSK, intent.getKioskId());
            }
        }
    }

    /** Kích hoạt sau khi tiền về: lỗi chỉ ghi lại (khách quét lại QR được), không làm hỏng việc ghi nhận tiền. */
    private void safeActivate(Order o, DeviceCommand.Source source, UUID kioskId) {
        try {
            ActivationService.Result r = activateCurrentLeg(o, source, kioskId, null);
            if (!r.ok()) events.record(o.getId(), "ACTIVATION_FAILED", o.getStatus(), o.getStatus(), Map.of("problem", r.problem()));
        } catch (BusinessException ex) {
            events.record(o.getId(), "ACTIVATION_FAILED", o.getStatus(), o.getStatus(), Map.of("problem", ex.getMessage()));
        }
    }

    private void confirm(Order o, Instant now) {
        OrderPolicy p = support.policy(o);
        if (o.getChannel() == Order.Channel.KIOSK) {
            List<OrderLeg> legs = support.legs(o.getId());
            OrderLeg first = legs.get(0);
            boolean immediate = !support.reservation(first).getStartsAt().isAfter(now.plusSeconds(30))
                    && !o.getScheduledStartAt().isAfter(now.plusSeconds(30));
            // Đơn đặt ngay, máy rảnh ngay: T = lúc trả xong (§6.2). Khoảng giữ thu về theo T thật; không giãn được
            // (trả sau giờ máy rảnh, sát lượt sau) thì giữ T cũ. Không được ném lỗi ở đây: tiền đã về.
            Timeline.Window w = Timeline.firstLeg(now, first.getCycleMinutes(), p);
            if (immediate && support.tryResize(first, support.reservation(first).getStartsAt(), w.end())) {
                o.setScheduledStartAt(now);
                o.setCheckinOpensAt(now);
                o.setCheckinDeadlineAt(now.plus(Duration.ofMinutes(p.graceMinutes())));
                first.setPlannedStartAt(now);
                first.setStartDeadlineAt(o.getCheckinDeadlineAt());
                if (legs.size() > 1) {
                    OrderLeg dryer = legs.get(1);
                    Timeline.Window dw = Timeline.dryerAfterWash(now, first.getCycleMinutes(), dryer.getCycleMinutes(), p);
                    if (support.tryResize(dryer, dw.start(), dw.end())) dryer.setPlannedStartAt(dw.start());
                }
            }
            setStatus(o, Order.Status.CONFIRMED, "CONFIRMED");
            notifications.order(o, Notification.Type.PAYMENT_RECEIVED, "Đã thanh toán",
                    "Biên nhận đơn " + o.getCode() + ": " + Money.format(o.getPaidAmount()) + ".", null);
            if (!o.getScheduledStartAt().isAfter(now.plusSeconds(30))) {
                safeActivate(o, DeviceCommand.Source.KIOSK, o.getKioskId());
            } else {
                notifications.confirmed(o);
            }
            return;
        }
        setStatus(o, Order.Status.CONFIRMED, "CONFIRMED");
        notifications.confirmed(o);
    }

    /** Kích hoạt chặng hiện tại (dùng khi tiền về tại kiosk, và cho nhân viên kích hoạt thủ công). */
    @Transactional(propagation = Propagation.MANDATORY)
    public ActivationService.Result activateCurrentLeg(Order o, DeviceCommand.Source source, UUID kioskId, String reason) {
        OrderPolicy p = support.policy(o);
        OrderLeg leg = support.legs(o.getId()).stream().filter(OrderLeg::notStarted).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_STATE, "Không còn chặng nào chờ kích hoạt"));
        return activation.activate(o, leg, source, kioskId, reason, p.startWindowMinutes());
    }

    // ==================================================================================
    // Nhả chỗ, hủy, không đến
    // ==================================================================================

    /** Hết 5 phút giữ chỗ mà chưa trả đủ tiền giữ chỗ: nhả máy, hoàn phần đã trừ ví (§7.3). */
    @Transactional
    public void expireHold(UUID orderId) {
        Order o = orders.lock(orderId).orElse(null);
        if (o == null || o.getStatus() != Order.Status.HOLD || o.getHoldExpiresAt().isAfter(clock.instant())) return;
        close(o, new CancellationRules.Outcome(Order.CancellationKind.UNPAID, refundable(o), 0,
                Refund.Reason.PAYMENT_ABANDONED), Order.Status.EXPIRED, "Hết thời gian giữ chỗ", null);
    }

    @Transactional
    public Order cancelByCustomer(UUID customerId, UUID orderId) {
        Order o = lockOwned(customerId, orderId);
        return cancel(o, "Khách hủy");
    }

    @Transactional
    public Order cancelAtKiosk(UUID orderId) {
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        if (o.getChannel() != Order.Channel.KIOSK) throw new BusinessException(ErrorCode.ORDER_STATE, "Đơn đặt trước hủy trên app");
        return cancel(o, "Hủy tại kiosk");
    }

    private Order cancel(Order o, String why) {
        if (!o.isOpen()) throw new BusinessException(ErrorCode.ORDER_STATE, "Đơn đã kết thúc");
        List<OrderLeg> legs = support.legs(o.getId());
        if (legs.stream().anyMatch(l -> !l.notStarted() && l.getStatus() != OrderLeg.Status.CANCELLED)) {
            throw new BusinessException(ErrorCode.ORDER_STATE, "Máy đã chạy, không hủy được");
        }
        Instant now = clock.instant();
        if (o.getStatus() != Order.Status.HOLD && now.isAfter(o.getCheckinDeadlineAt())) {
            throw new BusinessException(ErrorCode.CHECKIN_CLOSED, "Đã hết ân hạn; gặp nhân viên");
        }
        CancellationRules.Outcome outcome = CancellationRules.cancel(o, support.policy(o), now);
        close(o, outcome, Order.Status.CANCELLED, why, null);
        return o;
    }

    /** Nhân viên hủy: lỗi cửa hàng thì hoàn 100% và không tính vi phạm (§12); nếu không áp như khách hủy. */
    @Transactional
    public Order cancelByStaff(UUID orderId, boolean storeFault, String rawReason) {
        String reason = Reasons.require(rawReason);
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        if (!o.isOpen()) throw new BusinessException(ErrorCode.ORDER_STATE, "Đơn đã kết thúc");
        CancellationRules.Outcome outcome = storeFault
                ? new CancellationRules.Outcome(Order.CancellationKind.STORE_FAULT, refundable(o), 0, Refund.Reason.STORE_FAULT)
                : CancellationRules.cancel(o, support.policy(o), clock.instant());
        close(o, outcome, Order.Status.CANCELLED, reason, reason);
        audit.user("ORDER_CANCELLED_BY_STAFF", "order", orderId.toString(), reason, null,
                Map.of("storeFault", storeFault, "refund", outcome.refund()));
        return o;
    }

    /** Sự cố cửa hàng, không có máy thay thế: hoàn 100%, không tính vi phạm. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelForStoreFault(Order o, String reason) {
        close(o, new CancellationRules.Outcome(Order.CancellationKind.STORE_FAULT, refundable(o), 0,
                Refund.Reason.STORE_FAULT), Order.Status.CANCELLED, reason, null);
    }

    /**
     * Hủy riêng một chặng khi chặng khác của đơn đã chạy (combo: máy sấy hỏng sau khi giặt xong, quá hạn chuyển đồ...).
     * Hoàn {@code refund} (không quá số còn hoàn được); đơn kết thúc khi các chặng còn lại xong.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelLeg(Order o, OrderLeg leg, long refund, Refund.Reason reason, String why) {
        if (leg.getStatus() == OrderLeg.Status.ACTIVATED) activation.revoke(leg, null, why);
        leg.setStatus(OrderLeg.Status.CANCELLED);
        support.release(leg);
        long refundable = Math.max(0, o.getPaidAmount() - o.getRefundedAmount() - o.getForfeitedAmount());
        long amount = Math.min(refund, refundable);
        long forfeit = Math.min(Math.max(0, leg.getPriceAmount() - amount), refundable - amount);
        o.setForfeitedAmount(o.getForfeitedAmount() + forfeit);
        orders.saveAndFlush(o);
        refunds.auto(o, amount, reason, why);
        events.record(o.getId(), "LEG_CANCELLED", o.getStatus(), o.getStatus(),
                Map.of("legNo", leg.getLegNo(), "refund", amount, "reason", why));
        completeIfDone(o);
    }

    /** Mọi chặng đã xong / bị hủy: đơn hoàn tất. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completeIfDone(Order o) {
        if (!o.isOpen()) return;
        List<OrderLeg> legs = support.legs(o.getId());
        boolean closed = legs.stream().allMatch(l -> l.getStatus() == OrderLeg.Status.DONE
                || l.getStatus() == OrderLeg.Status.CANCELLED || l.getStatus() == OrderLeg.Status.INTERRUPTED);
        boolean used = legs.stream().anyMatch(l -> l.getStatus() == OrderLeg.Status.DONE);
        if (closed && used) {
            Order.Status before = o.getStatus();
            o.setStatus(Order.Status.COMPLETED);
            o.setCompletedAt(clock.instant());
            events.record(o.getId(), "COMPLETED", before, Order.Status.COMPLETED, Map.of());
        }
    }

    /** Hết ân hạn mà máy chưa chạy (§11): giữ tiền giữ chỗ, hoàn phần còn lại, trả máy ngay cho khách khác. */
    @Transactional
    public void noShow(UUID orderId) {
        Order o = orders.lock(orderId).orElse(null);
        if (o == null || (o.getStatus() != Order.Status.CONFIRMED && o.getStatus() != Order.Status.IN_PROGRESS)) return;
        Instant now = clock.instant();
        if (!now.isAfter(o.getCheckinDeadlineAt())) return;
        List<OrderLeg> legs = support.legs(o.getId());
        if (legs.stream().anyMatch(l -> !l.notStarted() && l.getStatus() != OrderLeg.Status.CANCELLED)) return;
        // Vừa kích hoạt lần cuối trong ân hạn: khách còn thời hạn bấm START.
        if (legs.stream().anyMatch(l -> l.getStatus() == OrderLeg.Status.ACTIVATED
                && l.getActivationExpiresAt() != null && l.getActivationExpiresAt().isAfter(now))) return;
        // Lượt có hạn riêng còn hiệu lực (nhân viên cho chạy lại sau sự cố).
        if (legs.stream().anyMatch(l -> l.notStarted() && l.getStartDeadlineAt() != null
                && l.getStartDeadlineAt().isAfter(now))) return;
        close(o, CancellationRules.noShow(o, support.policy(o)), Order.Status.NO_SHOW, "Không đến", null);
    }

    // ==================================================================================
    // Đổi giờ (§11.1)
    // ==================================================================================

    @Transactional
    public Order reschedule(UUID customerId, UUID orderId, Instant startAt, UUID washerId, UUID dryerId) {
        support.lockSchedule();
        Order o = lockOwned(customerId, orderId);
        OrderPolicy p = support.policy(o);
        Instant now = clock.instant();
        if (!CancellationRules.canReschedule(o, p, now)) {
            throw new BusinessException(ErrorCode.RESCHEDULE_NOT_ALLOWED,
                    "Chỉ đổi giờ miễn phí 1 lần, trước giờ bắt đầu " + p.freeCancelBeforeMinutes() + " phút");
        }
        StoreSettings s = store.settings();
        Instant start = startAt.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        String timeProblem = availability.timeProblem(s, start);
        if (timeProblem != null) throw new BusinessException(ErrorCode.ADVANCE_LIMIT, timeProblem);
        List<OrderLeg> legs = support.legs(o.getId());
        UUID currentWasher = legs.stream().filter(l -> l.getMachineType() == Machine.Type.WASHER)
                .map(OrderLeg::getMachineId).findFirst().orElse(null);
        UUID currentDryer = legs.stream().filter(l -> l.getMachineType() == Machine.Type.DRYER)
                .map(OrderLeg::getMachineId).findFirst().orElse(null);
        UUID w = washerId != null ? washerId : currentWasher;
        UUID d = dryerId != null ? dryerId : currentDryer;
        AvailabilityService.AdvanceResult avail = availability.advance(o.getService(), start, w, o.getId());
        List<Machine> chosen = pickMachines(o.getService(), w, d, avail);
        Instant before = o.getScheduledStartAt();
        for (int i = 0; i < legs.size(); i++) {
            OrderLeg leg = legs.get(i);
            Machine m = chosen.get(i);
            if (!m.getMachineType().equals(leg.getMachineType())) throw new BusinessException(ErrorCode.MACHINE_TYPE_MISMATCH);
            if (m.getPriceVnd() != leg.getPriceAmount()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Đổi giờ chỉ chọn được máy cùng giá");
            }
            Timeline.Window win = i == 0 ? Timeline.firstLeg(start, m.getCycleMinutes(), p)
                    : Timeline.dryerAfterWash(start, chosen.get(0).getCycleMinutes(), m.getCycleMinutes(), p);
            support.move(leg, m.getId(), win.start(), win.end());
            leg.setPlannedStartAt(win.start());
            leg.setCycleMinutes(m.getCycleMinutes());
            leg.setCapacityKg(m.getCapacityKg());
            if (i == 0) leg.setStartDeadlineAt(start.plus(Duration.ofMinutes(p.graceMinutes())));
        }
        o.setScheduledStartAt(start);
        o.setCheckinOpensAt(start.minus(Duration.ofMinutes(p.earlyCheckinMinutes())));
        o.setCheckinDeadlineAt(start.plus(Duration.ofMinutes(p.graceMinutes())));
        o.setRescheduleCount(o.getRescheduleCount() + 1);
        notifications.cancelReminders(o.getId());
        notifications.confirmed(o);
        events.record(o.getId(), "RESCHEDULED", o.getStatus(), o.getStatus(),
                Map.of("from", before.toString(), "to", start.toString()));
        return o;
    }

    // ==================================================================================
    // Nội bộ
    // ==================================================================================

    /** Đóng đơn: nhả máy, hủy phiếu thu, ghi phần giữ lại, hoàn phần còn lại. */
    private void close(Order o, CancellationRules.Outcome outcome, Order.Status status, String why, String staffReason) {
        Order.Status before = o.getStatus();
        Instant now = clock.instant();
        for (OrderLeg leg : support.legs(o.getId())) {
            if (leg.notStarted()) {
                if (leg.getStatus() == OrderLeg.Status.ACTIVATED) activation.revoke(leg, null, why);
                leg.setStatus(OrderLeg.Status.CANCELLED);
                support.release(leg);
            }
        }
        payments.cancelPending(o.getId());
        o.setForfeitedAmount(o.getForfeitedAmount() + outcome.forfeit());
        o.setStatus(status);
        o.setCancellationKind(outcome.kind());
        o.setCancelledAt(now);
        o.setCancelReason(why);
        com.laundry.security.Actor.optionalUserId().ifPresent(o::setCancelledBy);
        orders.saveAndFlush(o);
        refunds.auto(o, outcome.refund(), outcome.reason(), why);
        notifications.cancelReminders(o.getId());
        if (before != Order.Status.HOLD) {
            notifications.order(o, Notification.Type.ORDER_CANCELLED, "Đơn " + o.getCode() + " đã kết thúc",
                    why + (outcome.refund() > 0 ? ". Hoàn " + Money.format(outcome.refund()) : "")
                            + (outcome.forfeit() > 0 ? ". Giữ lại " + Money.format(outcome.forfeit()) + " tiền giữ chỗ" : "")
                            + ".", null);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", outcome.kind());
        data.put("refund", outcome.refund());
        data.put("forfeit", outcome.forfeit());
        if (staffReason != null) data.put("reason", staffReason);
        events.record(o.getId(), status.name(), before, status, data);
    }

    private long refundable(Order o) {
        return Math.max(0, o.getPaidAmount() - o.getRefundedAmount() - o.getForfeitedAmount());
    }

    private Order newOrder(Order.Channel channel, Order.Service service, OrderPolicy p, Instant start) {
        Order o = new Order();
        String code;
        do {
            code = Tokens.orderCode();
        } while (orders.existsByCode(code));
        o.setCode(code);
        o.setQrTokenHash(QrCodes.hash(qr.orderToken(o.getId())));
        o.setChannel(channel);
        o.setService(service);
        o.setStatus(Order.Status.HOLD);
        o.setScheduledStartAt(start);
        o.setPolicySnapshot(support.json(p));
        return o;
    }

    private void createLegs(Order o, List<Machine> chosen, Instant start, OrderPolicy p) {
        for (int i = 0; i < chosen.size(); i++) {
            Machine m = chosen.get(i);
            Timeline.Window win = i == 0 ? Timeline.firstLeg(start, m.getCycleMinutes(), p)
                    : Timeline.dryerAfterWash(start, chosen.get(0).getCycleMinutes(), m.getCycleMinutes(), p);
            MachineReservation r = support.reserve(m.getId(), win.start(), win.end());
            legRepository.save(leg(o, i + 1, m, r, win.start(),
                    i == 0 ? start.plus(Duration.ofMinutes(p.graceMinutes())) : null));
        }
        legRepository.flush();
    }

    /** Đặt ngay: chặng đầu giữ từ bây giờ (gồm 5' thanh toán) hoặc từ lúc máy rảnh. */
    private void createLegsWalkIn(Order o, List<Machine> chosen, Instant holdFrom, Instant worstT, OrderPolicy p) {
        Machine first = chosen.get(0);
        Timeline.Window w = Timeline.firstLeg(worstT, first.getCycleMinutes(), p);
        MachineReservation r = support.reserve(first.getId(), holdFrom, w.end());
        legRepository.save(leg(o, 1, first, r, holdFrom, o.getCheckinDeadlineAt()));
        if (chosen.size() > 1) {
            Machine dryer = chosen.get(1);
            Timeline.Window dw = Timeline.dryerAfterWash(worstT, first.getCycleMinutes(), dryer.getCycleMinutes(), p);
            MachineReservation dr = support.reserve(dryer.getId(), dw.start(), dw.end());
            legRepository.save(leg(o, 2, dryer, dr, dw.start(), null));
        }
        legRepository.flush();
    }

    private static OrderLeg leg(Order o, int no, Machine m, MachineReservation r, Instant planned, Instant deadline) {
        OrderLeg l = new OrderLeg();
        l.setOrderId(o.getId());
        l.setLegNo(no);
        l.setMachineType(m.getMachineType());
        l.setMachineId(m.getId());
        l.setReservationId(r.getId());
        l.setPriceAmount(m.getPriceVnd());
        l.setCycleMinutes(m.getCycleMinutes());
        l.setCapacityKg(m.getCapacityKg());
        l.setPlannedStartAt(planned);
        l.setStartDeadlineAt(deadline);
        return l;
    }

    private List<Machine> pickMachines(Order.Service service, UUID washerId, UUID dryerId,
                                       AvailabilityService.AdvanceResult avail) {
        List<Machine> out = new ArrayList<>();
        if (AvailabilityService.needs(service, Machine.Type.WASHER)) out.add(pick(washerId, avail.washers(), Machine.Type.WASHER));
        if (AvailabilityService.needs(service, Machine.Type.DRYER)) out.add(pick(dryerId, avail.dryers(), Machine.Type.DRYER));
        return out;
    }

    private Machine pick(UUID id, List<AvailabilityService.Option> options, Machine.Type type) {
        if (id == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Chọn " + label(type));
        AvailabilityService.Option o = options.stream().filter(x -> x.machineId().equals(id)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.MACHINE_TYPE_MISMATCH, "Máy không phải " + label(type)));
        availability.requireBookable(o);
        return machines.findById(id).orElseThrow();
    }

    private Order lockOwned(UUID customerId, UUID orderId) {
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        if (!customerId.equals(o.getCustomerId())) throw BusinessException.notFound("đơn");
        return o;
    }

    private void setStatus(Order o, Order.Status status, String event) {
        Order.Status before = o.getStatus();
        o.setStatus(status);
        events.record(o.getId(), event, before, status, Map.of("paid", o.getPaidAmount()));
    }

    private static void requireServiceEnabled(StoreSettings s, Order.Service service) {
        boolean on = switch (service) {
            case WASH -> s.isWashEnabled();
            case DRY -> s.isDryEnabled();
            case WASH_DRY -> s.isWashDryEnabled();
            case LAUNDRY -> s.isLaundryServiceEnabled();
        };
        if (!on || service == Order.Service.LAUNDRY) throw new BusinessException(ErrorCode.SERVICE_DISABLED);
    }

    private static String label(Machine.Type t) {
        return t == Machine.Type.WASHER ? "máy giặt" : "máy sấy";
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
