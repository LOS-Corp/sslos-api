package com.laundry.refund.service;

import com.laundry.kiosk.KioskAccounts;
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

    /** Marks refunds owed to a walk-in customer of the kiosk in the Owner's pending list. */
    public static final String KIOSK_REASON_PREFIX = "Khách vãng lai (kiosk) — ";

    private final PaymentRepository payments;
    private final RefundRequestRepository refunds;
    private final WalletService wallet;
    private final KioskAccounts kioskAccounts;
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
     *
     * <p>A kiosk booking (V72) has no customer wallet: the walk-in customer paid at the kiosk. The amount is then
     * raised as a refund request waiting for the Owner, who pays the person back in hand; the returned amount is
     * what was queued.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Money settle(UUID transactionId, UUID customerId, Money amount, WalletEntryType type,
                        String reason) {
        boolean walkIn = kioskAccounts.isKiosk(customerId);
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
            if (walkIn) {
                refunds.save(RefundRequest.raise(payment.getId(), transactionId, customerId,
                        BigDecimal.valueOf(part.vnd()), KIOSK_REASON_PREFIX + reason, null, OffsetDateTime.now(clock)));
                log.info("Kiosk refund of {} VND for transaction {} queued for the Owner", part.vnd(), transactionId);
                credited = credited.plus(part);
                continue;
            }
            RefundRequest refund = refunds.save(RefundRequest.settled(payment.getId(), transactionId,
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
