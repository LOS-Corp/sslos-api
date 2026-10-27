package com.laundry.transaction.repository;

import com.laundry.transaction.entity.MachineReservation;
import com.laundry.transaction.entity.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Machine reservation repository
 */
@Repository
public interface MachineReservationRepository extends JpaRepository<MachineReservation, UUID> {

    @Query(value = """
        SELECT COUNT(*)
        FROM booking_resource_allocations a
        JOIN scheduled_bookings b ON b.id = a.booking_id
        WHERE a.resource_id = :machineId
          AND a.occupied_from < :endTime
          AND a.occupied_until > :startTime
          AND (b.status <> 'HELD' OR b.hold_expires_at > NOW())
          AND booking_holds_resources(b.status)
        """, nativeQuery = true)
    long countScheduledConflicts(@Param("machineId") UUID machineId,
                                 @Param("startTime") OffsetDateTime startTime,
                                 @Param("endTime") OffsetDateTime endTime);

    List<MachineReservation> findByMachineIdAndStatus(UUID machineId, ReservationStatus status);

    List<MachineReservation> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    List<MachineReservation> findAllByTransactionId(UUID transactionId);

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

    /**
     * BR-PAY-002: Expire reservations due to payment timeout (single transaction).
     */
    @Modifying
    @Query("UPDATE MachineReservation mr SET mr.status = 'EXPIRED', mr.updatedAt = :now " +
           "WHERE mr.transaction.id = :transactionId AND mr.status = 'RESERVED'")
    int expireByTransactionId(
        @Param("transactionId") UUID transactionId,
        @Param("now") LocalDateTime now
    );

    /**
     * Bulk variant — used by the expiration scheduler to release all reservations
     * owned by a set of expired transactions in a single round-trip.
     * Only flips RESERVED → EXPIRED; CONFIRMED/ACTIVE/EXPIRED/CANCELLED are untouched.
     */
    @Modifying
    @Query("UPDATE MachineReservation mr SET mr.status = 'EXPIRED', mr.updatedAt = :now " +
           "WHERE mr.transaction.id IN :transactionIds AND mr.status = 'RESERVED'")
    int expireByTransactionIds(
        @Param("transactionIds") List<UUID> transactionIds,
        @Param("now") LocalDateTime now
    );

    /**
     * Close out CONFIRMED/ACTIVE reservations whose window ended before
     * {@code cutoff} and whose machine was actually started (a CONSUMED
     * start authorization exists) → COMPLETED.
     */
    @Modifying
    @Query("UPDATE MachineReservation mr SET mr.status = 'COMPLETED', mr.updatedAt = :now " +
           "WHERE mr.status IN ('CONFIRMED', 'ACTIVE') AND mr.endTime < :cutoff " +
           "AND EXISTS (SELECT 1 FROM MachineStartAuthorization msa " +
           "            WHERE msa.reservationId = mr.id AND msa.status = :consumed)")
    int completeEnded(@Param("cutoff") LocalDateTime cutoff,
                      @Param("now") LocalDateTime now,
                      @Param("consumed") com.laundry.machine.entity.MachineStartAuthorizationStatus consumed);

    /**
     * Remaining CONFIRMED/ACTIVE reservations past their window were never
     * used (no-show) → EXPIRED. Run after {@link #completeEnded}.
     */
    @Modifying
    @Query("UPDATE MachineReservation mr SET mr.status = 'EXPIRED', mr.updatedAt = :now " +
           "WHERE mr.status IN ('CONFIRMED', 'ACTIVE') AND mr.endTime < :cutoff")
    int expireEnded(@Param("cutoff") LocalDateTime cutoff,
                    @Param("now") LocalDateTime now);
}
