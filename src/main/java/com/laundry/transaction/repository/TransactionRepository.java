package com.laundry.transaction.repository;

import com.laundry.transaction.entity.Transaction;
import com.laundry.transaction.entity.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Transaction repository
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Transaction t WHERE t.id = :id")
    Optional<Transaction> findByIdForUpdate(@Param("id") UUID id);

    List<Transaction> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    List<Transaction> findByStatus(TransactionStatus status);

    @Query("SELECT t FROM Transaction t WHERE t.status = :status AND t.expiresAt < :now")
    List<Transaction> findExpiredTransactions(
        @Param("status") TransactionStatus status,
        @Param("now") LocalDateTime now
    );

    /**
     * Projection: IDs of PENDING_PAYMENT transactions whose deadline has passed.
     * Bounded by a {@link Pageable} so memory and lock time per scheduler tick are
     * predictable. The scheduler passes {@link PageRequest#ofSize(int)} with the
     * configured batch size.
     */
    @Query(value = "SELECT t.id FROM Transaction t " +
                   "WHERE t.status = :status AND t.expiresAt < :now " +
                   "ORDER BY t.expiresAt ASC",
           countQuery = "SELECT COUNT(t.id) FROM Transaction t " +
                        "WHERE t.status = :status AND t.expiresAt < :now")
    Page<UUID> findExpiredTransactionIdsPage(@Param("status") TransactionStatus status,
                                               @Param("now") LocalDateTime now,
                                               Pageable pageable);

    /**
     * Convenience wrapper used by the scheduler — returns the IDs in a fixed-size
     * window (oldest deadlines first) capped at {@code batchSize}.
     */
    default List<UUID> findExpiredTransactionIds(TransactionStatus status, LocalDateTime now, int batchSize) {
        return findExpiredTransactionIdsPage(status, now, PageRequest.ofSize(Math.max(1, batchSize))).getContent();
    }

    /**
     * Conditional bulk UPDATE: flip PENDING_PAYMENT → EXPIRED only for rows
     * still in PENDING_PAYMENT status. A payment callback that arrives
     * between the SELECT and this UPDATE causes the corresponding row to be
     * skipped (no risk of clobbering a freshly-PAID transaction).
     *
     * <p>Idempotent: running this twice still results in EXPIRED, never in a
     * wrong state.
     */
    @Modifying
    @Query("UPDATE Transaction t SET t.status = :newStatus, t.updatedAt = :now " +
           "WHERE t.id IN :ids AND t.status = :currentStatus")
    int updateStatusBulk(
        @Param("ids") List<UUID> ids,
        @Param("currentStatus") TransactionStatus currentStatus,
        @Param("newStatus") TransactionStatus newStatus,
        @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("UPDATE Transaction t SET t.status = :newStatus, t.updatedAt = :now WHERE t.id = :id AND t.status = :currentStatus")
    int updateStatus(
        @Param("id") UUID id,
        @Param("currentStatus") TransactionStatus currentStatus,
        @Param("newStatus") TransactionStatus newStatus,
        @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("UPDATE Transaction t SET t.status = :newStatus, t.paidAt = :paidAt, t.updatedAt = :now WHERE t.id = :id AND t.status = 'PENDING_PAYMENT'")
    int confirmPayment(
        @Param("id") UUID id,
        @Param("newStatus") TransactionStatus newStatus,
        @Param("paidAt") LocalDateTime paidAt,
        @Param("now") LocalDateTime now
    );
}
