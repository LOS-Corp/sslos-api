package com.laundry.payment.repository;

import com.laundry.payment.entity.Payment;
import com.laundry.payment.entity.PaymentStatus;
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
 * Payment repository
 */
@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByTransactionIdOrderByCreatedAtDesc(UUID transactionId);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByGatewayTransactionId(String gatewayTransactionId);

    Optional<Payment> findFirstByTransactionIdAndStatus(UUID transactionId, PaymentStatus status);

    @Modifying
    @Query("UPDATE Payment p SET p.status = :status, p.updatedAt = :now WHERE p.id = :id AND p.status = 'PENDING'")
    int updateStatusToPaid(
        @Param("id") UUID id,
        @Param("status") PaymentStatus status,
        @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("UPDATE Payment p SET p.status = 'FAILED', p.gatewayResponse = :reason, p.updatedAt = :now WHERE p.id = :id AND p.status = 'PENDING'")
    int updateStatusToFailed(
        @Param("id") UUID id,
        @Param("reason") String reason,
        @Param("now") LocalDateTime now
    );
}
