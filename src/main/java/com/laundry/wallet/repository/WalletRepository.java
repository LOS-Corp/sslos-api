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

    /**
     * Customers whose ledger disagrees with the documents behind it (NFR-17): refund credits against completed
     * refund rows, wallet debits against wallet payments, reversal credits against reversed wallet payments.
     */
    @Query(value = """
            WITH ledger AS (
                SELECT w.customer_id,
                       COALESCE(SUM(e.amount_vnd) FILTER (WHERE e.type IN ('REFUND_CREDIT', 'LATE_PAYMENT_CREDIT')), 0) refunded,
                       -COALESCE(SUM(e.amount_vnd) FILTER (WHERE e.type = 'PAYMENT_DEBIT'), 0) debited,
                       COALESCE(SUM(e.amount_vnd) FILTER (WHERE e.type = 'PAYMENT_REVERSAL'), 0) reversed
                FROM wallets w JOIN wallet_entries e ON e.wallet_id = w.id
                GROUP BY w.customer_id
            ), refund_rows AS (
                SELECT t.customer_id, SUM(r.amount) refunded
                FROM refund_requests r JOIN transactions t ON t.id = r.transaction_id
                WHERE r.status = 'COMPLETED'
                GROUP BY t.customer_id
            ), wallet_payments AS (
                SELECT t.customer_id,
                       SUM(p.amount) FILTER (WHERE p.status IN ('PAID', 'REVERSED')) debited,
                       SUM(p.amount) FILTER (WHERE p.status = 'REVERSED') reversed
                FROM payments p JOIN transactions t ON t.id = p.transaction_id
                WHERE p.payment_method = 'WALLET'
                GROUP BY t.customer_id
            )
            SELECT l.customer_id FROM ledger l
            LEFT JOIN refund_rows r ON r.customer_id = l.customer_id
            LEFT JOIN wallet_payments p ON p.customer_id = l.customer_id
            WHERE l.refunded <> COALESCE(r.refunded, 0)
               OR l.debited <> COALESCE(p.debited, 0)
               OR l.reversed <> COALESCE(p.reversed, 0)
            """, nativeQuery = true)
    List<UUID> findUnreconciledCustomerIds();
}
