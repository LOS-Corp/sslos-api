package com.laundry.transaction.repository;

import com.laundry.transaction.entity.LaundryServiceOrder;
import com.laundry.transaction.entity.LaundryOrderStatus;
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
 * Laundry service order repository
 */
@Repository
public interface LaundryServiceOrderRepository extends JpaRepository<LaundryServiceOrder, UUID> {

    Optional<LaundryServiceOrder> findByTransactionId(UUID transactionId);

    List<LaundryServiceOrder> findByStatus(LaundryOrderStatus status);

    @Modifying
    @Query("UPDATE LaundryServiceOrder lo SET lo.status = :status, lo.updatedAt = :now WHERE lo.id = :id")
    int updateStatus(
        @Param("id") UUID id,
        @Param("status") LaundryOrderStatus status,
        @Param("now") LocalDateTime now
    );

    /**
     * Bulk variant — used by the expiration scheduler to cancel laundry orders
     * belonging to a set of expired transactions in a single UPDATE.
     */
    @Modifying
    @Query("UPDATE LaundryServiceOrder lo SET lo.status = 'CANCELLED', lo.updatedAt = :now " +
           "WHERE lo.transaction.id IN :transactionIds AND lo.status IN ('PENDING', 'RECEIVED', 'PROCESSING')")
    int cancelByTransactionIds(@Param("transactionIds") List<UUID> transactionIds,
                                 @Param("now") LocalDateTime now);
}
