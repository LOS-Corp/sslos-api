package com.laundry.payment;

import com.laundry.common.AfterCommit;
import com.laundry.common.Tokens;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.order.Order;
import com.laundry.order.OrderRepository;
import com.laundry.refund.Refund;
import com.laundry.refund.RefundService;
import com.laundry.security.Actor;
import com.laundry.wallet.Wallet;
import com.laundry.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Thu tiền (§14).
 * <ul>
 *   <li>Phiếu thu có số tiền cố định và hạn; ví trừ trước (ghi nhận ngay), phần thiếu qua QR payOS.</li>
 *   <li>Tiền payOS về đúng hạn, đúng số mới ghi nhận; về muộn / sai số / phiếu đã đóng: không ghi nhận, tự hoàn.</li>
 *   <li>Mỗi khoản chỉ thu một lần: webhook lặp bị chặn ở payment_webhook_events và UNIQUE(method, reference).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    /** Tiền về trễ vài giây so với hạn QR (độ trễ ngân hàng) vẫn nhận. */
    static final long LATE_TOLERANCE_SECONDS = 30;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyMMdd").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    private final PaymentIntentRepository intents;
    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final WalletService wallets;
    private final RefundService refunds;
    private final PayOsGateway gateway;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * Tạo phiếu thu cho đơn (đã khóa). Ví đủ thì trả xong ngay ({@link PaymentEvents.IntentPaid} phát luôn).
     * Phiếu đang mở cùng mục đích bị thay thế.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentIntent create(Order order, PaymentIntent.Purpose purpose, long amount, boolean useWallet,
                                Instant expiresAt) {
        if (amount <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Không có khoản nào cần trả");
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now)) throw new BusinessException(ErrorCode.PAYMENT_EXPIRED);
        if (purpose != PaymentIntent.Purpose.DRYER_EXTENSION) {
            intents.pending(order.getId()).stream().filter(i -> i.getPurpose() == purpose
                    || isOrderPayment(i.getPurpose()) && isOrderPayment(purpose)).forEach(this::cancelInternal);
            intents.flush();
        }
        PaymentIntent intent = new PaymentIntent();
        intent.setOrderId(order.getId());
        intent.setPurpose(purpose);
        intent.setAmount(amount);
        intent.setExpiresAt(expiresAt);
        Actor.current().ifPresent(a -> {
            if (a instanceof Actor.User u) intent.setCreatedBy(u.id());
            if (a instanceof Actor.Kiosk k) intent.setKioskId(k.id());
        });
        long fromWallet = 0;
        if (useWallet && order.getCustomerId() != null && wallets.exists(order.getCustomerId())) {
            Wallet w = wallets.get(order.getCustomerId());
            fromWallet = Math.min(w.getBalance(), amount);
        }
        intent.setWalletAmount(fromWallet);
        intent.setGatewayAmount(amount - fromWallet);
        intents.saveAndFlush(intent);
        if (fromWallet > 0) {
            Payment p = newPayment(order.getId(), intent.getId(), Payment.Method.WALLET, fromWallet, now);
            p.setStatus(Payment.Status.CAPTURED);
            payments.saveAndFlush(p);
            wallets.debitPayment(order.getCustomerId(), fromWallet, p.getId(), "Đơn " + order.getCode());
            order.setPaidAmount(order.getPaidAmount() + fromWallet);
        }
        if (intent.getGatewayAmount() == 0) {
            markPaid(intent, now);
            return intent;
        }
        Long code = jdbc.queryForObject("select nextval('payos_order_code_seq')", Long.class);
        intent.setPayosOrderCode(code);
        PayOsGateway.Link link = gateway.create(code, intent.getGatewayAmount(), "SSLOS " + order.getCode(), expiresAt);
        intent.setCheckoutUrl(link.checkoutUrl());
        intent.setQrCode(link.qrCode());
        intent.setPayosPaymentLinkId(link.paymentLinkId());
        return intent;
    }

    /** Tiền payOS về (webhook hoặc giả lập). Trả về payment đã ghi (CAPTURED hoặc REJECTED). */
    @Transactional
    public Optional<Payment> onGatewayPaid(long orderCode, long amount, String reference, String payerBin,
                                           String payerAccount, String payerName, Long webhookEventId) {
        if (amount <= 0 || reference == null || reference.isBlank()) return Optional.empty();
        List<UUID> seen = jdbc.queryForList("select id from payments where method = 'PAYOS' and provider_reference = ?",
                UUID.class, reference);
        if (!seen.isEmpty()) return Optional.empty();   // cùng giao dịch ngân hàng: đã xử lý
        Instant now = clock.instant();
        Optional<PaymentIntent> found = intents.lockByOrderCode(orderCode);
        Payment p = newPayment(found.map(PaymentIntent::getOrderId).orElse(null),
                found.map(PaymentIntent::getId).orElse(null), Payment.Method.PAYOS, amount, now);
        p.setProviderReference(reference);
        p.setPayerBankBin(payerBin);
        p.setPayerAccountNumber(payerAccount);
        p.setPayerAccountName(payerName);
        p.setWebhookEventId(webhookEventId);
        if (found.isEmpty()) {
            return Optional.of(reject(p, Payment.RejectReason.UNKNOWN_ORDER, Refund.Reason.AMOUNT_MISMATCH));
        }
        PaymentIntent intent = found.get();
        if (intent.getStatus() == PaymentIntent.Status.PAID) {
            return Optional.of(reject(p, Payment.RejectReason.INTENT_CLOSED, Refund.Reason.LATE_PAYMENT));
        }
        if (intent.getStatus() != PaymentIntent.Status.PENDING
                || now.isAfter(intent.getExpiresAt().plusSeconds(LATE_TOLERANCE_SECONDS))) {
            return Optional.of(reject(p, Payment.RejectReason.LATE, Refund.Reason.LATE_PAYMENT));
        }
        if (amount != intent.getGatewayAmount()) {
            return Optional.of(reject(p, Payment.RejectReason.AMOUNT_MISMATCH, Refund.Reason.AMOUNT_MISMATCH));
        }
        Order order = orders.lock(intent.getOrderId()).orElseThrow();
        p.setStatus(Payment.Status.CAPTURED);
        payments.saveAndFlush(p);
        order.setPaidAmount(order.getPaidAmount() + amount);
        markPaid(intent, now);
        return Optional.of(p);
    }

    /** Phiếu quá hạn: EXPIRED, hủy link payOS, báo module đơn. */
    @Transactional
    public void expire(UUID intentId) {
        intents.lock(intentId).filter(i -> i.getStatus() == PaymentIntent.Status.PENDING).ifPresent(i -> {
            i.setStatus(PaymentIntent.Status.EXPIRED);
            Long code = i.getPayosOrderCode();
            if (code != null) AfterCommit.run(() -> gateway.cancel(code, "Hết hạn"));
            events.publishEvent(new PaymentEvents.IntentExpired(i.getId(), i.getOrderId(), i.getPurpose()));
        });
    }

    /** Hủy các phiếu đang mở của đơn (đơn bị hủy / nhả chỗ). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelPending(UUID orderId) {
        intents.pending(orderId).forEach(this::cancelInternal);
    }

    public List<UUID> expiredIntents() {
        return intents.expired(clock.instant());
    }

    public List<PaymentIntent> intentsOf(UUID orderId) {
        return intents.findByOrderIdOrderByCreatedAt(orderId);
    }

    public List<Payment> paymentsOf(UUID orderId) {
        return payments.findByOrderIdOrderByReceivedAt(orderId);
    }

    public PaymentIntent intent(UUID id) {
        return intents.findById(id).orElseThrow(() -> BusinessException.notFound("phiếu thu"));
    }

    /** Tổng đã trừ ví cho một phiếu (để hoàn khi phiếu bị bỏ dở). */
    public long walletCaptured(UUID orderId, UUID intentId) {
        return payments.capturedFor(orderId, intentId).stream()
                .filter(p -> p.getMethod() == Payment.Method.WALLET).mapToLong(Payment::getAmount).sum();
    }

    private void markPaid(PaymentIntent intent, Instant now) {
        intent.setStatus(PaymentIntent.Status.PAID);
        intent.setPaidAt(now);
        events.publishEvent(new PaymentEvents.IntentPaid(intent.getId(), intent.getOrderId(), intent.getPurpose()));
    }

    private Payment reject(Payment p, Payment.RejectReason why, Refund.Reason refundReason) {
        p.setStatus(Payment.Status.REJECTED);
        p.setRejectReason(why);
        payments.saveAndFlush(p);
        log.warn("Payment {} rejected ({}): {} đ ref {}", p.getReceiptNo(), why, p.getAmount(), p.getProviderReference());
        refunds.forRejectedPayment(p, refundReason);
        return p;
    }

    private void cancelInternal(PaymentIntent i) {
        if (i.getStatus() != PaymentIntent.Status.PENDING) return;
        i.setStatus(PaymentIntent.Status.CANCELLED);
        Long code = i.getPayosOrderCode();
        if (code != null) AfterCommit.run(() -> gateway.cancel(code, "Thay bằng phiếu mới"));
    }

    private Payment newPayment(UUID orderId, UUID intentId, Payment.Method method, long amount, Instant now) {
        Payment p = new Payment();
        p.setReceiptNo("BN" + DAY.format(now) + "-" + Tokens.orderCode() + Tokens.orderCode().substring(0, 2));
        p.setOrderId(orderId);
        p.setPaymentIntentId(intentId);
        p.setMethod(method);
        p.setAmount(amount);
        p.setReceivedAt(now);
        return p;
    }

    private static boolean isOrderPayment(PaymentIntent.Purpose p) {
        return p == PaymentIntent.Purpose.DEPOSIT || p == PaymentIntent.Purpose.FULL || p == PaymentIntent.Purpose.BALANCE;
    }
}
