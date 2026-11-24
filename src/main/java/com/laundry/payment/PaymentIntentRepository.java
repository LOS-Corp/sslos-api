package com.laundry.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PaymentIntent i where i.id = :id")
    Optional<PaymentIntent> lock(UUID id);

    /** Đọc phiếu theo mã (không khóa), cho trang chuyển sang SePay. */
    Optional<PaymentIntent> findByPayosOrderCode(Long payosOrderCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from PaymentIntent i where i.payosOrderCode = :code")
    Optional<PaymentIntent> lockByOrderCode(long code);

    List<PaymentIntent> findByOrderIdOrderByCreatedAt(UUID orderId);

    @Query("""
            select i from PaymentIntent i where i.orderId = :orderId
              and i.status = com.laundry.payment.PaymentIntent.Status.PENDING
            """)
    List<PaymentIntent> pending(UUID orderId);

    @Query("""
            select i.id from PaymentIntent i where i.status = com.laundry.payment.PaymentIntent.Status.PENDING
              and i.expiresAt < :now
            """)
    List<UUID> expired(Instant now);
}
