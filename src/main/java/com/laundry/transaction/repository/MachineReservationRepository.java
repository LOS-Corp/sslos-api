package com.laundry.transaction.repository;

import com.laundry.transaction.entity.MachineReservation;
import com.laundry.transaction.entity.ReservationStatus;
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
 * Machine reservation repository
 */
@Repository
public interface MachineReservationRepository extends JpaRepository<MachineReservation, UUID> {

    List<MachineReservation> findByMachineIdAndStatus(UUID machineId, ReservationStatus status);

    Optional<MachineReservation> findByTransactionId(UUID transactionId);

    @Query("SELECT mr FROM MachineReservation mr WHERE mr.machineId = :machineId " +
           "AND mr.status IN ('RESERVED', 'CONFIRMED', 'ACTIVE') " +
           "AND mr.startTime < :endTime AND (mr.endTime IS NULL OR mr.endTime > :startTime)")
    List<MachineReservation> findOverlappingReservations(
        @Param("machineId") UUID machineId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );

    @Modifying
    @Query("UPDATE MachineReservation mr SET mr.status = :status, mr.updatedAt = :now WHERE mr.id = :id")
    int updateStatus(
        @Param("id") UUID id,
        @Param("status") ReservationStatus status,
        @Param("now") LocalDateTime now
    );

    @Modifying
    @Query("UPDATE MachineReservation mr SET mr.status = 'CANCELLED', mr.updatedAt = :now " +
           "WHERE mr.transaction.id = :transactionId AND mr.status = 'RESERVED'")
    int cancelByTransactionId(
        @Param("transactionId") UUID transactionId,
        @Param("now") LocalDateTime now
    );
}
