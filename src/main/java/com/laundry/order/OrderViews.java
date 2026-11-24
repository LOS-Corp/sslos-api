package com.laundry.order;

import com.laundry.machine.Machine;
import com.laundry.machine.MachineRepository;
import com.laundry.payment.Payment;
import com.laundry.payment.PaymentIntent;
import com.laundry.payment.PaymentService;
import com.laundry.refund.Refund;
import com.laundry.refund.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Dữ liệu trả cho app / kiosk / web quản lý. */
@Component
@RequiredArgsConstructor
public class OrderViews {

    private final OrderLegRepository legs;
    private final MachineRepository machines;
    private final PaymentService payments;
    private final RefundService refunds;
    private final OrderEventLog events;
    private final QrCodes qr;

    public record LegView(int legNo, Machine.Type machineType, UUID machineId, String machineCode, long priceAmount,
                          int cycleMinutes, BigDecimal capacityKg, OrderLeg.Status status, Instant plannedStartAt,
                          Instant startDeadlineAt, Instant activationExpiresAt, Instant startedAt, Instant expectedEndAt,
                          Instant finishedAt, int extraMinutes, String interruptionReason) {
    }

    public record IntentView(UUID id, PaymentIntent.Purpose purpose, long amount, long walletAmount, long gatewayAmount,
                             PaymentIntent.Status status, String qrCode, String checkoutUrl, Instant expiresAt,
                             Instant paidAt, PaymentIntent.Gateway gateway) {
        public static IntentView of(PaymentIntent i) {
            return i == null ? null : new IntentView(i.getId(), i.getPurpose(), i.getAmount(), i.getWalletAmount(),
                    i.getGatewayAmount(), i.getStatus(), i.getQrCode(), i.getCheckoutUrl(), i.getExpiresAt(), i.getPaidAt(),
                    i.getGateway());
        }
    }

    /** Biên nhận (§14). */
    public record ReceiptView(String receiptNo, Payment.Method method, long amount, Payment.Status status,
                              Payment.RejectReason rejectReason, Instant receivedAt) {
    }

    public record RefundView(UUID id, long amount, Refund.Reason reason, Refund.Destination destination,
                             Refund.Status status, String note, Instant createdAt, Instant completedAt) {
        public static RefundView of(Refund r) {
            return new RefundView(r.getId(), r.getAmount(), r.getReasonCode(), r.getDestination(), r.getStatus(),
                    r.getReasonText(), r.getCreatedAt(), r.getCompletedAt());
        }
    }

    public record OrderView(UUID id, String code, String qrToken, Order.Channel channel, Order.Service service,
                            Order.Status status, Instant scheduledStartAt, Instant holdExpiresAt, Instant checkinOpensAt,
                            Instant checkinDeadlineAt, long totalAmount, long depositAmount, long paidAmount,
                            long refundedAmount, long forfeitedAmount, long remainingAmount, int rescheduleCount,
                            Order.CancellationKind cancellationKind, String cancelReason, Instant createdAt,
                            Instant completedAt, Instant cancelledAt, List<LegView> legs, IntentView pendingPayment,
                            List<ReceiptView> receipts, List<RefundView> refunds) {
    }

    /** {@code withQr}: chỉ chủ đơn (app) và kiosk vừa bán (biên nhận) mới thấy mã QR. */
    @Transactional(readOnly = true)
    public OrderView view(Order o, boolean withQr) {
        List<OrderLeg> ls = legs.findByOrderIdOrderByLegNo(o.getId());
        Map<UUID, String> codes = machines.findAllById(ls.stream().map(OrderLeg::getMachineId).toList()).stream()
                .collect(Collectors.toMap(Machine::getId, Machine::getCode));
        PaymentIntent pending = payments.intentsOf(o.getId()).stream()
                .filter(i -> i.getStatus() == PaymentIntent.Status.PENDING).reduce((a, b) -> b).orElse(null);
        return new OrderView(o.getId(), o.getCode(), withQr ? qr.orderToken(o.getId()) : null, o.getChannel(),
                o.getService(), o.getStatus(), o.getScheduledStartAt(), o.getHoldExpiresAt(), o.getCheckinOpensAt(),
                o.getCheckinDeadlineAt(), o.getTotalAmount(), o.getDepositAmount(), o.getPaidAmount(),
                o.getRefundedAmount(), o.getForfeitedAmount(), o.remainingAmount(), o.getRescheduleCount(),
                o.getCancellationKind(), o.getCancelReason(), o.getCreatedAt(), o.getCompletedAt(), o.getCancelledAt(),
                ls.stream().map(l -> new LegView(l.getLegNo(), l.getMachineType(), l.getMachineId(),
                        codes.get(l.getMachineId()), l.getPriceAmount(), l.getCycleMinutes(), l.getCapacityKg(),
                        l.getStatus(), l.getPlannedStartAt(), l.getStartDeadlineAt(), l.getActivationExpiresAt(),
                        l.getStartedAt(), l.getExpectedEndAt(), l.getFinishedAt(), l.getExtraMinutes(),
                        l.getInterruptionReason())).toList(),
                IntentView.of(pending),
                payments.paymentsOf(o.getId()).stream().map(p -> new ReceiptView(p.getReceiptNo(), p.getMethod(),
                        p.getAmount(), p.getStatus(), p.getRejectReason(), p.getReceivedAt())).toList(),
                refunds.ofOrder(o.getId()).stream().map(RefundView::of).toList());
    }

    public List<OrderEventLog.EventView> history(UUID orderId) {
        return events.history(orderId);
    }

}
