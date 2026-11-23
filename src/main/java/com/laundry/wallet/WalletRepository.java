package com.laundry.wallet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.userId = :userId")
    Optional<Wallet> lock(UUID userId);

    @Modifying
    @Query(value = "insert into wallets (user_id) values (:userId) on conflict do nothing", nativeQuery = true)
    void insertIfMissing(UUID userId);

    /** Ví có số dư khác tổng sổ cái (đối soát §15.2). */
    @Query(value = """
            select w.user_id as userId, w.balance as balance, w.held_balance as held,
                   coalesce(sum(t.amount), 0) as ledgerBalance, coalesce(sum(t.held_delta), 0) as ledgerHeld
            from wallets w left join wallet_transactions t on t.user_id = w.user_id
            group by w.user_id, w.balance, w.held_balance
            having w.balance <> coalesce(sum(t.amount), 0) or w.held_balance <> coalesce(sum(t.held_delta), 0)
            """, nativeQuery = true)
    List<Mismatch> mismatches();

    interface Mismatch {
        UUID getUserId();

        long getBalance();

        long getHeld();

        long getLedgerBalance();

        long getLedgerHeld();
    }
}
