package com.laundry.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DryerExtensionRepository extends JpaRepository<DryerExtension, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DryerExtension> findByPaymentIntentId(UUID paymentIntentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DryerExtension> findByDeviceCommandId(UUID deviceCommandId);

    List<DryerExtension> findByOrderLegIdOrderByCreatedAt(UUID orderLegId);

    @Query("""
            select e.id from DryerExtension e where e.status = com.laundry.order.DryerExtension.Status.PAID
              and e.updatedAt < :before
            """)
    List<UUID> unconfirmed(Instant before);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from DryerExtension e where e.id = :id")
    Optional<DryerExtension> lock(UUID id);
}
