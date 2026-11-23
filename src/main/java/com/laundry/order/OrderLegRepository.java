package com.laundry.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderLegRepository extends JpaRepository<OrderLeg, UUID> {

    List<OrderLeg> findByOrderIdOrderByLegNo(UUID orderId);

    List<OrderLeg> findByOrderIdInOrderByLegNo(Collection<UUID> orderIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from OrderLeg l where l.id = :id")
    Optional<OrderLeg> lock(UUID id);

    @Query("select l from OrderLeg l where l.machineId = :machineId and l.status in :statuses order by l.plannedStartAt")
    List<OrderLeg> onMachine(UUID machineId, Collection<OrderLeg.Status> statuses);

    @Query("select l from OrderLeg l where l.status = :status")
    List<OrderLeg> withStatus(OrderLeg.Status status);

    @Query("""
            select l from OrderLeg l where l.status = com.laundry.order.OrderLeg.Status.ACTIVATED
              and l.activationExpiresAt < :now
            """)
    List<OrderLeg> activationExpired(Instant now);

    @Query("""
            select l from OrderLeg l where l.status = com.laundry.order.OrderLeg.Status.AWAITING_UNLOAD
              and l.finishedAt < :before
            """)
    List<OrderLeg> unloadOver(Instant before);

    /** Chặng sắp tới (chưa chạy) trong [from, to): dùng để chuyển máy khi máy mất kết nối. */
    @Query("""
            select l from OrderLeg l where l.status = com.laundry.order.OrderLeg.Status.SCHEDULED
              and l.plannedStartAt < :to and l.plannedStartAt >= :from
            """)
    List<OrderLeg> upcoming(Instant from, Instant to);
}
