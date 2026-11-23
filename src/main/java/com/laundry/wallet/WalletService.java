package com.laundry.wallet;

import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.security.Actor;
import com.laundry.wallet.WalletTransaction.Type;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Mọi biến động ví đi qua đây: khóa dòng ví, đổi số dư, ghi một dòng sổ cái trong cùng transaction.
 * idempotencyKey duy nhất: gọi lại cùng khóa trả về dòng cũ, không cộng/trừ lần hai.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository wallets;
    private final WalletTransactionRepository ledger;

    @Transactional
    public void open(UUID userId) {
        wallets.insertIfMissing(userId);
    }

    @Transactional(readOnly = true)
    public Wallet get(UUID userId) {
        return wallets.findById(userId).orElseThrow(() -> BusinessException.notFound("ví"));
    }

    public boolean exists(UUID userId) {
        return wallets.existsById(userId);
    }

    /** Tiền hoàn vào ví (§15.1). */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction creditRefund(UUID userId, long amount, UUID refundId, String note) {
        return apply(userId, Type.REFUND, amount, 0, "refund:" + refundId, note, e -> e.setRefundId(refundId));
    }

    /** Bồi thường giặt hộ (§13.7): không phải hoàn tiền nên có thể vượt số đã trả. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction creditCompensation(UUID userId, long amount, UUID claimId, String note) {
        return apply(userId, Type.COMPENSATION, amount, 0, "claim:" + claimId, note, e -> e.setClaimId(claimId));
    }

    /** Trả đơn bằng ví; thiếu tiền thì lỗi, không trừ một phần. */
    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction debitPayment(UUID userId, long amount, UUID paymentId, String note) {
        return apply(userId, Type.PAYMENT, -amount, 0, "payment:" + paymentId, note, e -> e.setPaymentId(paymentId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction holdForWithdrawal(UUID userId, long amount, UUID withdrawalId) {
        return apply(userId, Type.WITHDRAWAL_HOLD, -amount, amount, "withdraw-hold:" + withdrawalId, null,
                e -> e.setWithdrawalId(withdrawalId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction releaseWithdrawal(UUID userId, long amount, UUID withdrawalId) {
        return apply(userId, Type.WITHDRAWAL_RELEASE, amount, -amount, "withdraw-release:" + withdrawalId, null,
                e -> e.setWithdrawalId(withdrawalId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public WalletTransaction payoutWithdrawal(UUID userId, long amount, UUID withdrawalId) {
        return apply(userId, Type.WITHDRAWAL_PAYOUT, 0, -amount, "withdraw-payout:" + withdrawalId, null,
                e -> e.setWithdrawalId(withdrawalId));
    }

    private WalletTransaction apply(UUID userId, Type type, long amount, long heldDelta, String key, String note,
                                    java.util.function.Consumer<WalletTransaction> link) {
        Optional<WalletTransaction> done = ledger.findByIdempotencyKey(key);
        if (done.isPresent()) return done.get();
        wallets.insertIfMissing(userId);
        Wallet w = wallets.lock(userId).orElseThrow(() -> BusinessException.notFound("ví"));
        long balance = w.getBalance() + amount;
        long held = w.getHeldBalance() + heldDelta;
        if (balance < 0) throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        if (held < 0) throw new IllegalStateException("Held balance would go negative for " + userId);
        w.setBalance(balance);
        w.setHeldBalance(held);
        WalletTransaction e = new WalletTransaction();
        e.setUserId(userId);
        e.setEntryType(type);
        e.setAmount(amount);
        e.setHeldDelta(heldDelta);
        e.setBalanceAfter(balance);
        e.setHeldAfter(held);
        e.setIdempotencyKey(key);
        e.setNote(note);
        e.setCreatedBy(Actor.optionalUserId().orElse(null));
        link.accept(e);
        return ledger.save(e);
    }
}
