package com.laundry.refund;

import com.laundry.audit.AuditService;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.notification.NotificationService;
import com.laundry.order.Order;
import com.laundry.order.OrderRepository;
import com.laundry.payment.Payment;
import com.laundry.payment.PaymentRepository;
import com.laundry.security.Actor;
import com.laundry.security.Role;
import com.laundry.store.StoreService;
import com.laundry.wallet.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hoàn tiền (§15.1).
 * <ul>
 *   <li>Theo quy tắc (AUTO): tự động. Khách có tài khoản: vào ví ngay. Khách vãng lai: chuyển về tài khoản đã chuyển
 *       tiền (nhân viên chuyển rồi bấm hoàn tất); không biết tài khoản thì nhận tại quầy.</li>
 *   <li>Ngoại lệ (EXCEPTION): nhân viên tạo kèm lý do; đến hạn mức (100.000 đ) nhân viên tự duyệt, trên hạn mức chủ
 *       cửa hàng duyệt.</li>
 * </ul>
 * Tổng hoàn của một đơn không vượt số khách đã trả: kiểm ở đây và chặn lần nữa bằng trigger DB.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final RefundRepository refunds;
    private final OrderRepository orders;
    private final PaymentRepository payments;
    private final WalletService wallets;
    private final StoreService store;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    /** Hoàn theo quy tắc cho một đơn; amount <= 0 thì bỏ qua. Gọi khi đã khóa dòng đơn. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Refund> auto(Order order, long amount, Refund.Reason reason, String note) {
        if (amount <= 0) return Optional.empty();
        long refundable = order.getPaidAmount() - order.getRefundedAmount() - order.getForfeitedAmount();
        if (amount > refundable) {
            throw new BusinessException(ErrorCode.REFUND_EXCEEDS_PAID,
                    "Hoàn " + amount + " vượt số còn hoàn được " + refundable + " của đơn " + order.getCode());
        }
        Refund r = base(order, amount, Refund.Kind.AUTO, reason, note);
        r.setStatus(Refund.Status.APPROVED);
        refunds.saveAndFlush(r);
        order.setRefundedAmount(order.getRefundedAmount() + amount);
        execute(r);
        audit.system("REFUND_AUTO", "order", order.getId().toString(), reason.name(), null,
                Map.of("amount", amount, "destination", r.getDestination()));
        return Optional.of(r);
    }

    /** Tiền về nhưng không được ghi nhận cho đơn (muộn / sai số): hoàn đúng khoản đó. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund forRejectedPayment(Payment p, Refund.Reason reason) {
        Refund r = new Refund();
        r.setPaymentId(p.getId());
        r.setAmount(p.getAmount());
        r.setKind(Refund.Kind.AUTO);
        r.setReasonCode(reason);
        Order order = p.getOrderId() == null ? null : orders.findById(p.getOrderId()).orElse(null);
        if (order != null && order.getCustomerId() != null) {
            r.setDestination(Refund.Destination.WALLET);
            r.setRecipientUserId(order.getCustomerId());
        } else if (p.getPayerAccountNumber() != null) {
            r.setDestination(Refund.Destination.BANK_TRANSFER);
            r.setBankBin(p.getPayerBankBin());
            r.setBankAccountNumber(p.getPayerAccountNumber());
            r.setBankAccountName(p.getPayerAccountName());
        } else {
            r.setDestination(Refund.Destination.COUNTER);
        }
        r.setStatus(Refund.Status.APPROVED);
        refunds.saveAndFlush(r);
        execute(r);
        audit.system("REFUND_REJECTED_PAYMENT", "payment", p.getId().toString(), reason.name(), null,
                Map.of("amount", p.getAmount()));
        return r;
    }

    /** Hoàn ngoại lệ (khiếu nại...). Trên hạn mức nhân viên: chờ chủ cửa hàng duyệt. */
    @Transactional
    public Refund exception(UUID orderId, long amount, String reasonText) {
        String reason = Reasons.require(reasonText);
        if (amount <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Số tiền phải lớn hơn 0");
        Order order = orders.lock(orderId).orElseThrow(() -> BusinessException.notFound("đơn"));
        long pendingOthers = refunds.findByOrderIdOrderByCreatedAt(orderId).stream()
                .filter(x -> x.getStatus() == Refund.Status.PENDING_APPROVAL).mapToLong(Refund::getAmount).sum();
        long refundable = order.getPaidAmount() - order.getRefundedAmount() - order.getForfeitedAmount() - pendingOthers;
        // Phần cửa hàng đã giữ (hủy muộn) vẫn hoàn ngoại lệ được, miễn tổng hoàn không vượt số đã trả.
        long cap = order.getPaidAmount() - order.getRefundedAmount() - pendingOthers;
        if (amount > cap) throw new BusinessException(ErrorCode.REFUND_EXCEEDS_PAID);
        Actor.User staff = Actor.user();
        Refund r = base(order, amount, Refund.Kind.EXCEPTION, Refund.Reason.EXCEPTION, reason);
        r.setRequestedBy(staff.id());
        long limit = store.settings().getStaffRefundLimitVnd();
        boolean selfApprove = amount <= limit || staff.has(Role.OWNER);
        if (selfApprove) {
            approveInternal(order, r, staff.id(), amount > refundable);
        } else {
            r.setStatus(Refund.Status.PENDING_APPROVAL);
            refunds.save(r);
        }
        audit.user("REFUND_EXCEPTION_CREATED", "order", orderId.toString(), reason, null,
                Map.of("amount", amount, "status", r.getStatus()));
        return r;
    }

    @Transactional
    public Refund approve(UUID refundId) {
        Refund r = refunds.lock(refundId).orElseThrow(() -> BusinessException.notFound("khoản hoàn"));
        if (r.getStatus() != Refund.Status.PENDING_APPROVAL) throw new BusinessException(ErrorCode.ORDER_STATE, "Khoản hoàn đã được xử lý");
        Order order = orders.lock(r.getOrderId()).orElseThrow();
        long refundable = order.getPaidAmount() - order.getRefundedAmount() - order.getForfeitedAmount();
        approveInternal(order, r, Actor.userId(), r.getAmount() > refundable);
        audit.user("REFUND_APPROVED", "refund", refundId.toString(), "Duyệt hoàn ngoại lệ", null,
                Map.of("amount", r.getAmount()));
        return r;
    }

    @Transactional
    public Refund reject(UUID refundId, String reasonText) {
        String reason = Reasons.require(reasonText);
        Refund r = refunds.lock(refundId).orElseThrow(() -> BusinessException.notFound("khoản hoàn"));
        if (r.getStatus() != Refund.Status.PENDING_APPROVAL) throw new BusinessException(ErrorCode.ORDER_STATE, "Khoản hoàn đã được xử lý");
        r.setStatus(Refund.Status.REJECTED);
        r.setRejectedReason(reason);
        r.setApprovedBy(Actor.userId());
        r.setApprovedAt(clock.instant());
        audit.user("REFUND_REJECTED", "refund", refundId.toString(), reason, null, Map.of("amount", r.getAmount()));
        return r;
    }

    /** Nhân viên đã chuyển khoản / trả tại quầy cho khoản hoàn không vào ví. */
    @Transactional
    public Refund complete(UUID refundId, String transferReference, UUID proofAttachmentId) {
        Refund r = refunds.lock(refundId).orElseThrow(() -> BusinessException.notFound("khoản hoàn"));
        if (r.getStatus() != Refund.Status.APPROVED || r.getDestination() == Refund.Destination.WALLET) {
            throw new BusinessException(ErrorCode.ORDER_STATE, "Khoản hoàn không chờ chuyển tiền");
        }
        r.setStatus(Refund.Status.COMPLETED);
        r.setCompletedBy(Actor.userId());
        r.setCompletedAt(clock.instant());
        r.setTransferReference(transferReference);
        r.setProofAttachmentId(proofAttachmentId);
        audit.user("REFUND_PAID_OUT", "refund", refundId.toString(),
                "Đã trả khoản hoàn qua " + r.getDestination(), null,
                Map.of("amount", r.getAmount(), "reference", String.valueOf(transferReference)));
        return r;
    }

    public Page<Refund> byStatus(Collection<Refund.Status> statuses, int page, int size) {
        return refunds.byStatus(statuses, PageRequest.of(page, Math.min(size, 100)));
    }

    public java.util.List<Refund> ofOrder(UUID orderId) {
        return refunds.findByOrderIdOrderByCreatedAt(orderId);
    }

    // ---- nội bộ ----

    private void approveInternal(Order order, Refund r, UUID approver, boolean touchesForfeit) {
        r.setStatus(Refund.Status.APPROVED);
        r.setApprovedBy(approver);
        r.setApprovedAt(clock.instant());
        refunds.saveAndFlush(r);
        if (touchesForfeit) {
            // Hoàn lại cả phần cửa hàng đã giữ: giảm forfeited để giữ ràng buộc refunded + forfeited <= paid.
            long excess = r.getAmount() - (order.getPaidAmount() - order.getRefundedAmount() - order.getForfeitedAmount());
            order.setForfeitedAmount(Math.max(0, order.getForfeitedAmount() - excess));
        }
        order.setRefundedAmount(order.getRefundedAmount() + r.getAmount());
        execute(r);
    }

    private Refund base(Order order, long amount, Refund.Kind kind, Refund.Reason reason, String note) {
        Refund r = new Refund();
        r.setOrderId(order.getId());
        r.setAmount(amount);
        r.setKind(kind);
        r.setReasonCode(reason);
        r.setReasonText(note);
        if (order.getCustomerId() != null && wallets.exists(order.getCustomerId())) {
            r.setDestination(Refund.Destination.WALLET);
            r.setRecipientUserId(order.getCustomerId());
        } else {
            Optional<Payment> payer = payments.lastPayerAccount(order.getId());
            if (payer.isPresent()) {
                r.setDestination(Refund.Destination.BANK_TRANSFER);
                r.setBankBin(payer.get().getPayerBankBin());
                r.setBankAccountNumber(payer.get().getPayerAccountNumber());
                r.setBankAccountName(payer.get().getPayerAccountName());
            } else {
                r.setDestination(Refund.Destination.COUNTER);
            }
        }
        return r;
    }

    /** Vào ví thì xong ngay; chuyển khoản / tại quầy chờ nhân viên thực hiện (status APPROVED). */
    private void execute(Refund r) {
        if (r.getDestination() == Refund.Destination.WALLET) {
            wallets.creditRefund(r.getRecipientUserId(), r.getAmount(), r.getId(), r.getReasonCode().name());
            r.setStatus(Refund.Status.COMPLETED);
            r.setCompletedAt(clock.instant());
        }
        notifications.refund(r);
    }
}
