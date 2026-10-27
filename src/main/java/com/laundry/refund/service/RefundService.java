package com.laundry.refund.service;

import com.laundry.audit.service.AuditAction;
import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditEntry;
import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.refund.dto.request.CreateRefundRequest;
import com.laundry.refund.dto.response.RefundResponse;
import com.laundry.refund.entity.RefundRequest;
import com.laundry.refund.repository.RefundRequestRepository;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.security.CurrentUser;
import com.laundry.user.service.BranchScopeService;
import com.laundry.shared.money.Money;
import com.laundry.transaction.entity.Transaction;
import com.laundry.transaction.repository.TransactionRepository;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Exception refunds (UC-08 alt 4a, FR-REF-05). Staff or an Owner raise a request with evidence;
 * an Owner who did not raise it approves or rejects it; an approved refund is credited to the
 * customer's wallet in the same transaction and becomes COMPLETED (BR-15). Identity and branch always come from the
 * token and the payment, never from the request body (BR-01, SC-23). The sum of refunds of a payment
 * never exceeds the amount paid (BR-14, FR-PAY-03), checked under a lock on the payment row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundService {

    private static final String ENTITY = "REFUND_REQUEST";

    private final RefundRequestRepository refunds;
    private final PaymentRepository payments;
    private final TransactionRepository transactions;
    private final WalletService wallet;
    private final BranchScopeService branchScope;
    private final AuditService audit;
    private final Clock clock;

    @Transactional
    public RefundResponse raise(CreateRefundRequest request, CurrentUser requester) {
        Payment payment = payments.findByIdForUpdate(request.paymentId())
                .orElseThrow(() -> BusinessException.notFound("Payment not found"));
        if (payment.getStatus() != PaymentStatus.PAID) {
            throw BusinessException.of(ErrorCode.REFUND_REJECTED, "Only a paid payment can be refunded");
        }
        UUID branchId = refunds.findBranchOfTransaction(payment.getTransactionId())
                .orElseThrow(() -> BusinessException.of(ErrorCode.REFUND_REJECTED,
                        "The payment is not linked to a branch booking"));
        branchScope.require(requester, branchId);
        requireWithinCap(payment, request.amount());

        RefundRequest refund = refunds.save(RefundRequest.raise(payment.getId(), payment.getTransactionId(),
                branchId, requester.id(), request.amount(), request.reason().trim(), request.evidence(), now()));
        audit.record(AuditEntry.of(AuditAction.REFUND_REQUESTED, ENTITY, refund.getId(), branchId, requester)
                .withReason(refund.getReason())
                .withChange(null, Map.of("amount", refund.getAmount(), "paymentId", payment.getId())));
        log.info("Refund request {} raised for payment {} by {}", refund.getId(), payment.getId(), requester.id());
        return RefundResponse.from(refund);
    }

    @Transactional
    public RefundResponse approve(UUID refundId, String note, CurrentUser owner) {
        RefundRequest refund = lockedRefund(refundId);
        Payment payment = payments.findByIdForUpdate(refund.getPaymentId())
                .orElseThrow(() -> BusinessException.notFound("Payment not found"));
        BigDecimal othersCounted = countedRefunds(payment.getId(), refund.getId());
        if (othersCounted.add(refund.getAmount()).compareTo(payment.getAmount()) > 0) {
            throw BusinessException.of(ErrorCode.REFUND_EXCEEDS_PAID, "Total refunds would exceed the amount paid");
        }
        refund.approve(owner.id(), now());
        // BR-15: refunds go to the wallet by default, in the same transaction as the approval.
        UUID customerId = transactions.findById(refund.getTransactionId())
                .map(Transaction::getCustomerId)
                .orElse(null);
        if (customerId != null) {
            wallet.credit(customerId, Money.of(refund.getAmount()), WalletEntryType.REFUND_CREDIT,
                    "refund:" + refund.getId(), "Hoàn tiền ngoại lệ được chủ cửa hàng duyệt");
            refund.complete(now());
        }
        audit.record(AuditEntry.of(AuditAction.REFUND_APPROVED, ENTITY, refund.getId(), refund.getBranchId(), owner)
                .withReason(note)
                .withChange(Map.of("status", "PENDING_APPROVAL"), Map.of("status", refund.getStatus().name())));
        return RefundResponse.from(refund);
    }

    @Transactional
    public RefundResponse reject(UUID refundId, String reason, CurrentUser owner) {
        if (reason == null || reason.isBlank()) {
            throw BusinessException.of(ErrorCode.REASON_REQUIRED, "Please enter a reason for this action");
        }
        RefundRequest refund = lockedRefund(refundId);
        refund.reject(owner.id(), reason.trim(), now());
        audit.record(AuditEntry.of(AuditAction.REFUND_REJECTED, ENTITY, refund.getId(), refund.getBranchId(), owner)
                .withReason(reason.trim())
                .withChange(Map.of("status", "PENDING_APPROVAL"), Map.of("status", refund.getStatus().name())));
        return RefundResponse.from(refund);
    }

    @Transactional(readOnly = true)
    public RefundResponse get(UUID refundId, CurrentUser viewer) {
        RefundRequest refund = refunds.findById(refundId)
                .orElseThrow(() -> BusinessException.notFound("Refund request not found"));
        branchScope.require(viewer, refund.getBranchId());
        return RefundResponse.from(refund);
    }

    /** Pending requests the viewer may see; {@code branchId} narrows the list. */
    @Transactional(readOnly = true)
    public List<RefundResponse> pending(UUID branchId, CurrentUser viewer) {
        if (branchId != null) {
            branchScope.require(viewer, branchId);
            return refunds.findPendingByBranches(Set.of(branchId)).stream().map(RefundResponse::from).toList();
        }
        Optional<Set<UUID>> visible = branchScope.visibleBranches(viewer);
        List<RefundRequest> result = visible.isEmpty()
                ? refunds.findPending()
                : visible.get().isEmpty() ? List.of() : refunds.findPendingByBranches(visible.get());
        return result.stream().map(RefundResponse::from).toList();
    }

    private void requireWithinCap(Payment payment, BigDecimal amount) {
        if (countedRefunds(payment.getId(), null).add(amount).compareTo(payment.getAmount()) > 0) {
            throw BusinessException.of(ErrorCode.REFUND_EXCEEDS_PAID, "Total refunds would exceed the amount paid");
        }
    }

    private BigDecimal countedRefunds(UUID paymentId, UUID excludeRefundId) {
        return refunds.findByPaymentId(paymentId).stream()
                .filter(RefundRequest::countsTowardCap)
                .filter(r -> !r.getId().equals(excludeRefundId))
                .map(RefundRequest::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private RefundRequest lockedRefund(UUID refundId) {
        return refunds.findByIdForUpdate(refundId)
                .orElseThrow(() -> BusinessException.notFound("Refund request not found"));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
