package com.laundry.wallet.entity;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One credit wallet per customer (FR-WAL-01). {@code balanceVnd} is a cache of the ledger sum and is
 * only changed together with a new {@link WalletEntry}; it can never go negative (BR-15).
 */
@Entity
@Table(name = "wallets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false, unique = true, updatable = false)
    private UUID customerId;

    @Column(name = "balance_vnd", nullable = false)
    private long balanceVnd;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private long version;

    public static Wallet openFor(UUID customerId, OffsetDateTime now) {
        Wallet wallet = new Wallet();
        wallet.id = UUID.randomUUID();
        wallet.customerId = customerId;
        wallet.createdAt = now;
        wallet.updatedAt = now;
        return wallet;
    }

    public Money balance() {
        return Money.vnd(balanceVnd);
    }

    /** Applies a ledger amount (positive = credit, negative = debit). */
    void apply(Money amount, OffsetDateTime now) {
        long next = Math.addExact(balanceVnd, amount.vnd());
        if (next < 0) {
            throw BusinessException.of(ErrorCode.INSUFFICIENT_BALANCE, "Wallet balance is not enough");
        }
        balanceVnd = next;
        updatedAt = now;
    }
}
