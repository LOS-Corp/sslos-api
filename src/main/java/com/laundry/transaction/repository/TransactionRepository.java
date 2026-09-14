package com.laundry.transaction.repository;

import com.laundry.transaction.entity.Transaction;
import com.laundry.transaction.entity.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    List<Transaction> findByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    List<Transaction> findByStatus(TransactionStatus status);

    @Query("SELECT t FROM Transaction t WHERE t.status = :status AND t.expiresAt < :now")
    List<Transaction> findExpiredTransactions(
        @Param("status") TransactionStatus status,
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
