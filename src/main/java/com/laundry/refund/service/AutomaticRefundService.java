package com.laundry.refund.service;

import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.refund.entity.RefundRequest;
import com.laundry.refund.repository.RefundRequestRepository;
import com.laundry.shared.money.Money;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Refunds decided by a rule rather than by an Owner: cancellation, no-show, late payment, machine or operator
 * stop (FR-REF-05, SM-06 R1–R2). Each one leaves a COMPLETED refund row per payment it draws on, so the cap of
 * BR-14 counts it and a later exception refund cannot repeat it. The wallet credit commits with the row.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomaticRefundService {

    private final PaymentRepository payments;
    private final RefundRequestRepository refunds;
    private final WalletService wallet;
    private final Clock clock;

    /** What can still go back to the customer: paid payments minus every refund that counts against them. */
    @Transactional(readOnly = true)
    public Money refundable(UUID transactionId) {
        return paidPayments(transactionId).stream()
                .map(this::leftOn)
                .reduce(Money.ZERO, Money::plus);
    }

    /**
     * Credits up to {@code amount} to the customer's wallet. Callers guard against running twice with a status
     * change on the locked booking. Returns what was actually credited, which is less than {@code amount} only
     * when earlier refunds already used the rest of the cap.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Money settle(UUID transactionId, UUID branchId, UUID customerId, Money amount, WalletEntryType type,
                        String reason) {
        Money credited = Money.ZERO;
        for (Payment listed : paidPayments(transactionId)) {
            Money wanted = amount.minus(credited);
            if (!wanted.isPositive()) {
                break;
            }
            Payment payment = payments.findByIdForUpdate(listed.getId()).orElseThrow();
            Money left = leftOn(payment);
            Money part = left.compareTo(wanted) < 0 ? left : wanted;
            if (!part.isPositive()) {
                continue;
            }
            RefundRequest refund = refunds.save(RefundRequest.settled(payment.getId(), transactionId, branchId,
                    customerId, BigDecimal.valueOf(part.vnd()), reason, OffsetDateTime.now(clock)));
            wallet.credit(customerId, part, type, "refund:" + refund.getId(), reason);
            credited = credited.plus(part);
        }
        if (credited.compareTo(amount) < 0) {
            log.warn("Refund of transaction {} limited to {} of {} VND by the cap (BR-14)",
                    transactionId, credited.vnd(), amount.vnd());
        }
        return credited;
    }

    private List<Payment> paidPayments(UUID transactionId) {
        return payments.findByTransactionIdOrderByCreatedAtDesc(transactionId).stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.PAID)
                .toList();
    }

    private Money leftOn(Payment payment) {
        BigDecimal left = payment.getAmount().subtract(refunds.countedAgainstCap(payment.getId(), null));
        return left.signum() > 0 ? Money.of(left) : Money.ZERO;
    }
}
