package com.laundry.wallet.repository;

import com.laundry.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByCustomerId(UUID customerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.customerId = :customerId")
    Optional<Wallet> findByCustomerIdForUpdate(@Param("customerId") UUID customerId);

    /** Wallets whose cached balance differs from the sum of their ledger (NFR-17). */
    @Query(value = """
            SELECT w.id FROM wallets w
            LEFT JOIN wallet_entries e ON e.wallet_id = w.id
            GROUP BY w.id, w.balance_vnd
            HAVING w.balance_vnd <> COALESCE(SUM(e.amount_vnd), 0)
            """, nativeQuery = true)
    List<UUID> findOutOfBalanceWalletIds();
}
