package com.laundry.wallet.entity;

import com.laundry.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Immutable ledger line (BR-15). The database rejects UPDATE/DELETE and duplicate (wallet, type, reference). */
@Entity
@Immutable
@Table(name = "wallet_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WalletEntryType type;

    @Column(name = "amount_vnd", nullable = false)
    private long amountVnd;

    @Column(nullable = false, length = 80)
    private String reference;

    @Column(length = 200)
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** Records {@code amount} on {@code wallet} and updates its cached balance in the same step. */
    public static WalletEntry post(Wallet wallet, WalletEntryType type, Money amount, String reference,
                                   String description, OffsetDateTime now) {
        wallet.apply(amount, now);
        WalletEntry entry = new WalletEntry();
        entry.walletId = wallet.getId();
        entry.type = type;
        entry.amountVnd = amount.vnd();
        entry.reference = reference;
        entry.description = description;
        entry.createdAt = now;
        return entry;
    }
}
