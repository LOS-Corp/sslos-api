package com.laundry.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByOrderIdOrderByReceivedAt(UUID orderId);

    @Query("""
            select p from Payment p where p.orderId = :orderId and p.paymentIntentId = :intentId
              and p.status = com.laundry.payment.Payment.Status.CAPTURED
            """)
    List<Payment> capturedFor(UUID orderId, UUID intentId);

    /** Khoản chuyển khoản (payOS / SePay) gần nhất của đơn có thông tin tài khoản người chuyển (để hoàn cho khách vãng lai). */
    @Query("""
            select p from Payment p where p.orderId = :orderId and p.method <> com.laundry.payment.Payment.Method.WALLET
              and p.payerAccountNumber is not null order by p.receivedAt desc limit 1
            """)
    Optional<Payment> lastPayerAccount(UUID orderId);
}
