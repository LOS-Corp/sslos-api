package com.laundry.refund;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByOrderIdOrderByCreatedAt(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> lock(UUID id);

    @Query("select r from Refund r where r.status in :statuses order by r.createdAt")
    Page<Refund> byStatus(Collection<Refund.Status> statuses, Pageable pageable);

    boolean existsByPaymentId(UUID paymentId);
}
