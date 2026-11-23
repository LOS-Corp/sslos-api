package com.laundry.order;

import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.device.DeviceCommand;
import com.laundry.device.DeviceCommandService;
import com.laundry.device.DeviceEvents;
import com.laundry.machine.DryerTimePack;
import com.laundry.machine.DryerTimePackRepository;
import com.laundry.machine.Machine;
import com.laundry.payment.PaymentEvents;
import com.laundry.payment.PaymentIntent;
import com.laundry.payment.PaymentService;
import com.laundry.refund.Refund;
import com.laundry.refund.RefundService;
import com.laundry.store.StoreSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Mua thêm phút sấy khi máy sấy đang chạy (§8.4): chỉ bán khi phần thêm không chồng lên lượt kế tiếp, tối đa
 * {@code dryerExtensionMaxMinutes} mỗi lượt; trả xong mà máy không nhận lệnh thì hoàn 100% gói.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DryerExtensionService {

    private final DryerExtensionRepository extensions;
    private final DryerTimePackRepository packs;
    private final OrderRepository orders;
    private final OrderLegRepository legs;
    private final BookingSupport support;
    private final PaymentService payments;
    private final RefundService refunds;
    private final DeviceCommandService commands;
    private final OrderEventLog events;
    private final Clock clock;

    public record Purchase(DryerExtension extension, PaymentIntent intent) {
    }

    @Transactional
    public Purchase purchase(UUID orderId, int legNo, UUID packId, boolean useWallet, UUID customerId) {
        support.lockSchedule();
        Order o = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        if (customerId != null && !customerId.equals(o.getCustomerId())) throw BusinessException.notFound("đơn");
        OrderLeg leg = legs.findByOrderIdOrderByLegNo(orderId).stream().filter(l -> l.getLegNo() == legNo).findFirst()
                .flatMap(l -> legs.lock(l.getId())).orElseThrow(() -> BusinessException.notFound("chặng"));
        if (leg.getMachineType() != Machine.Type.DRYER || leg.getStatus() != OrderLeg.Status.RUNNING) {
            throw new BusinessException(ErrorCode.ORDER_STATE, "Chỉ mua thêm phút khi máy sấy đang chạy");
        }
        DryerTimePack pack = packs.findById(packId).filter(DryerTimePack::isActive)
                .filter(p -> p.getMachineId().equals(leg.getMachineId()))
                .orElseThrow(() -> BusinessException.notFound("gói sấy của máy này"));
        OrderPolicy p = support.policy(o);
        int pending = extensions.findByOrderLegIdOrderByCreatedAt(leg.getId()).stream()
                .filter(e -> e.getStatus() == DryerExtension.Status.PENDING_PAYMENT || e.getStatus() == DryerExtension.Status.PAID)
                .mapToInt(DryerExtension::getMinutes).sum();
        if (leg.getExtraMinutes() + pending + pack.getMinutes() > p.dryerExtensionMaxMinutes()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Mỗi lượt chỉ sấy thêm tối đa " + p.dryerExtensionMaxMinutes() + " phút");
        }
        var r = support.reservation(leg);
        if (!support.tryResize(leg, r.getStartsAt(), r.getEndsAt().plus(Duration.ofMinutes(pack.getMinutes())))) {
            throw new BusinessException(ErrorCode.MACHINE_UNAVAILABLE, "Máy có lượt kế tiếp, không thêm được thời gian");
        }
        DryerExtension ext = new DryerExtension();
        ext.setOrderLegId(leg.getId());
        ext.setPackId(pack.getId());
        ext.setMinutes(pack.getMinutes());
        ext.setPriceAmount(pack.getPriceVnd());
        extensions.saveAndFlush(ext);
        PaymentIntent intent = payments.create(o, PaymentIntent.Purpose.DRYER_EXTENSION, pack.getPriceVnd(), useWallet,
                clock.instant().plus(Duration.ofMinutes(StoreSettings.PAYMENT_HOLD_MINUTES)));
        ext.setPaymentIntentId(intent.getId());
        if (intent.getStatus() == PaymentIntent.Status.PAID) paid(ext, o, leg);   // ví trả đủ
        return new Purchase(ext, intent);
    }

    @EventListener
    public void onPaid(PaymentEvents.IntentPaid e) {
        if (e.purpose() != PaymentIntent.Purpose.DRYER_EXTENSION) return;
        extensions.findByPaymentIntentId(e.intentId()).filter(x -> x.getStatus() == DryerExtension.Status.PENDING_PAYMENT)
                .ifPresent(ext -> paid(ext, orders.lock(e.orderId()).orElseThrow(),
                        legs.lock(ext.getOrderLegId()).orElseThrow()));
    }

    private void paid(DryerExtension ext, Order o, OrderLeg leg) {
        if (ext.getStatus() != DryerExtension.Status.PENDING_PAYMENT) return;
        ext.setStatus(DryerExtension.Status.PAID);
        o.setTotalAmount(o.getTotalAmount() + ext.getPriceAmount());
        o.setDepositAmount(Math.min(o.getDepositAmount(), o.getTotalAmount()));
        UUID commandId = UUID.randomUUID();
        ext.setDeviceCommandId(commandId);
        boolean sent = false;
        try {
            sent = commands.trySend(commands.machineTarget(leg.getMachineId()), new DeviceCommandService.Spec(commandId,
                    "EXTEND_CYCLE", Map.of("extraSeconds", ext.getMinutes() * 60), DeviceCommand.Source.SYSTEM, null,
                    leg.getId(), null, null));
        } catch (RuntimeException ex) {
            log.warn("EXTEND_CYCLE for {} not sent: {}", o.getCode(), ex.getMessage());
        }
        if (!sent) fail(ext, o, leg, "Máy không nhận lệnh");
        else events.record(o.getId(), "DRYER_EXTENSION_PAID", o.getStatus(), o.getStatus(), Map.of("minutes", ext.getMinutes()));
    }

    @EventListener
    @Transactional
    public void onExpired(PaymentEvents.IntentExpired e) {
        if (e.purpose() != PaymentIntent.Purpose.DRYER_EXTENSION) return;
        extensions.findByPaymentIntentId(e.intentId()).filter(x -> x.getStatus() == DryerExtension.Status.PENDING_PAYMENT)
                .ifPresent(ext -> {
                    ext.setStatus(DryerExtension.Status.EXPIRED);
                    giveBack(legs.lock(ext.getOrderLegId()).orElseThrow(), ext.getMinutes());
                });
    }

    @EventListener
    public void onAck(DeviceEvents.CommandAck e) {
        if (e.commandId() == null || !"EXTEND_CYCLE".equals(e.command())) return;
        extensions.findByDeviceCommandId(e.commandId()).filter(x -> x.getStatus() == DryerExtension.Status.PAID)
                .ifPresent(ext -> {
                    OrderLeg leg = legs.lock(ext.getOrderLegId()).orElseThrow();
                    Order o = orders.lock(leg.getOrderId()).orElseThrow();
                    if (e.success()) {
                        ext.setStatus(DryerExtension.Status.APPLIED);
                        ext.setAppliedAt(clock.instant());
                        leg.setExtraMinutes(leg.getExtraMinutes() + ext.getMinutes());
                        if (leg.getExpectedEndAt() != null) {
                            leg.setExpectedEndAt(leg.getExpectedEndAt().plus(Duration.ofMinutes(ext.getMinutes())));
                        }
                        events.record(o.getId(), "DRYER_EXTENDED", o.getStatus(), o.getStatus(),
                                Map.of("minutes", ext.getMinutes()));
                    } else {
                        fail(ext, o, leg, "Máy từ chối: " + e.reason());
                    }
                });
    }

    /** Trả tiền rồi mà quá 1 phút máy không xác nhận: coi như không nhận lệnh. */
    @Transactional
    public void failIfUnconfirmed(UUID extId) {
        extensions.lock(extId).filter(x -> x.getStatus() == DryerExtension.Status.PAID)
                .filter(x -> x.getUpdatedAt().isBefore(clock.instant().minusSeconds(60)))
                .ifPresent(ext -> {
                    OrderLeg leg = legs.lock(ext.getOrderLegId()).orElseThrow();
                    fail(ext, orders.lock(leg.getOrderId()).orElseThrow(), leg, "Máy không xác nhận");
                });
    }

    private void fail(DryerExtension ext, Order o, OrderLeg leg, String why) {
        ext.setStatus(DryerExtension.Status.FAILED);
        giveBack(leg, ext.getMinutes());
        refunds.auto(o, ext.getPriceAmount(), Refund.Reason.EXTENSION_FAILED, why);
        events.record(o.getId(), "DRYER_EXTENSION_FAILED", o.getStatus(), o.getStatus(), Map.of("why", why));
    }

    private void giveBack(OrderLeg leg, int minutes) {
        var r = support.reservation(leg);
        Instant end = r.getEndsAt().minus(Duration.ofMinutes(minutes));
        support.tryResize(leg, r.getStartsAt(), end.isAfter(r.getStartsAt()) ? end : r.getStartsAt().plusSeconds(60));
    }

    @Component
    @ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
    @RequiredArgsConstructor
    public static class Job {
        private final DryerExtensionRepository extensions;
        private final DryerExtensionService service;
        private final Clock clock;

        @Scheduled(fixedDelay = 20_000, initialDelay = 30_000)
        public void run() {
            for (UUID id : extensions.unconfirmed(clock.instant().minusSeconds(60))) {
                try {
                    service.failIfUnconfirmed(id);
                } catch (RuntimeException e) {
                    log.warn("extension {} check failed: {}", id, e.getMessage());
                }
            }
        }
    }
}
