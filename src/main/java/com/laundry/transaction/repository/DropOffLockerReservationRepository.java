package com.laundry.transaction.repository;

import com.laundry.transaction.entity.DropOffLockerReservation;
import com.laundry.transaction.entity.LockerReservationStatus;
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
 * Drop-off locker reservation repository
 */
@Repository
public interface DropOffLockerReservationRepository extends JpaRepository<DropOffLockerReservation, UUID> {

    Optional<DropOffLockerReservation> findByLaundryOrderId(UUID laundryOrderId);

    List<DropOffLockerReservation> findByLockerIdAndStatus(UUID lockerId, LockerReservationStatus status);

    @Modifying
    @Query("UPDATE DropOffLockerReservation dlr SET dlr.status = :status, dlr.updatedAt = :now WHERE dlr.id = :id")
    int updateStatus(
        @Param("id") UUID id,
        @Param("status") LockerReservationStatus status,
        @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("UPDATE DropOffLockerReservation dlr SET dlr.status = 'CANCELLED', dlr.updatedAt = :now " +
           "WHERE dlr.laundryOrder.id = :orderId AND dlr.status = 'RESERVED'")
    int cancelByOrderId(
        @Param("orderId") UUID orderId,
        @Param("now") LocalDateTime now
    );
}
