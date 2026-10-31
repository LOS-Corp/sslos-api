package com.laundry.payment.service;

import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentMethod;
import com.laundry.payment.entity.PaymentStatus;
import com.laundry.payment.repository.PaymentRepository;
import com.laundry.shared.money.Money;
import com.laundry.wallet.entity.WalletEntryType;
import com.laundry.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The wallet part of an order payment (FR-PAY-04: wallet first, the remainder through payOS) and its
 * reversal when the rest of the payment does not happen (SC-31). Every method joins the caller's
 * transaction, so the debit, the payment row and the booking change commit together.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletPaymentService {

    private final PaymentRepository payments;
    private final WalletService wallet;
    private final Clock clock;

    /**
     * Debits up to {@code total} from the customer's wallet once per transaction and records it as a PAID
     * WALLET payment. Returns what the wallet covers (zero if the balance is empty).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Money payFromWallet(UUID customerId, UUID transactionId, Money total, String idempotencyKey) {
        Money already = walletPortion(transactionId);
        if (already.isPositive() || !total.isPositive()) {
            return activeWalletPart(transactionId);
        }
        Money balance = wallet.balanceOf(customerId);
        Money part = balance.compareTo(total) >= 0 ? total : balance;
        if (!part.isPositive()) {
            return Money.ZERO;
        }
        wallet.debit(customerId, part, WalletEntryType.PAYMENT_DEBIT, "payment:" + transactionId,
                "Thanh toán đơn bằng ví");
        LocalDateTime now = LocalDateTime.now(clock);
        Payment payment = Payment.builder()
                .transactionId(transactionId)
                .amount(java.math.BigDecimal.valueOf(part.vnd()))
                .paymentMethod(PaymentMethod.WALLET)
                .status(PaymentStatus.PAID)
                .idempotencyKey(idempotencyKey + ":wallet")
                .gatewayTransactionId("WALLET-" + transactionId)
                .paidAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        payments.save(payment);
        log.info("Wallet covered {} VND of transaction {}", part.vnd(), transactionId);
        return part;
    }

    /** Gives the wallet part back (SC-31) for transactions whose payment did not complete. Idempotent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverse(Collection<UUID> transactionIds, String why) {
        for (UUID transactionId : transactionIds) {
            for (Payment payment : walletPayments(transactionId)) {
                if (payment.getStatus() != PaymentStatus.PAID) {
                    continue;
                }
                UUID customerId = customerOf(transactionId);
                if (customerId != null) {
                    wallet.credit(customerId, Money.of(payment.getAmount()), WalletEntryType.PAYMENT_REVERSAL,
                            "payment-reversal:" + payment.getId(), why);
                }
                payment.setStatus(PaymentStatus.REVERSED);
                payment.setUpdatedAt(LocalDateTime.now(clock));
                payments.save(payment);
            }
        }
    }

    /** Everything ever paid from the wallet for this transaction, reversed or not. */
    public Money walletPortion(UUID transactionId) {
        return walletPayments(transactionId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.PAID || p.getStatus() == PaymentStatus.REVERSED)
                .map(p -> Money.of(p.getAmount()))
                .reduce(Money.ZERO, Money::plus);
    }

    /** The wallet part still held for this transaction (not reversed). */
    public Money activeWalletPart(UUID transactionId) {
        return walletPayments(transactionId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.PAID)
                .map(p -> Money.of(p.getAmount()))
                .reduce(Money.ZERO, Money::plus);
    }

    private List<Payment> walletPayments(UUID transactionId) {
        return payments.findByTransactionIdOrderByCreatedAtDesc(transactionId).stream()
                .filter(p -> p.getPaymentMethod() == PaymentMethod.WALLET)
                .toList();
    }

    private UUID customerOf(UUID transactionId) {
        return payments.findCustomerOfTransaction(transactionId).orElse(null);
    }
}
