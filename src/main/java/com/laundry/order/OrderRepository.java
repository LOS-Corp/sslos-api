package com.laundry.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> lock(UUID id);

    Optional<Order> findByQrTokenHash(String qrTokenHash);

    Optional<Order> findByCode(String code);

    boolean existsByCode(String code);

    Page<Order> findByCustomerIdOrderByCreatedAtDesc(UUID customerId, Pageable pageable);

    @Query("""
            select o from Order o where (:status is null or o.status = :status)
              and o.scheduledStartAt >= :from and o.scheduledStartAt < :to
            order by o.scheduledStartAt
            """)
    List<Order> findInRange(Order.Status status, Instant from, Instant to);

    @Query("select o.id from Order o where o.status = com.laundry.order.Order.Status.HOLD and o.holdExpiresAt < :now")
    List<UUID> expiredHolds(Instant now);

    /** Đơn đã qua ân hạn mà chưa có chặng nào chạy (job "không đến"). */
    @Query("""
            select o.id from Order o where o.status in :statuses and o.service <> com.laundry.order.Order.Service.LAUNDRY
              and o.checkinDeadlineAt < :now
              and not exists (select l from OrderLeg l where l.orderId = o.id and l.status in
                  (com.laundry.order.OrderLeg.Status.RUNNING, com.laundry.order.OrderLeg.Status.AWAITING_UNLOAD,
                   com.laundry.order.OrderLeg.Status.DONE, com.laundry.order.OrderLeg.Status.INTERRUPTED))
            """)
    List<UUID> pastDeadlineNotStarted(Collection<Order.Status> statuses, Instant now);

    @Query("""
            select o.cancelledAt from Order o where o.customerId = :customerId and o.countsAsViolation = true
              and o.cancelledAt >= :since order by o.cancelledAt
            """)
    List<Instant> violationTimes(UUID customerId, Instant since);
}
