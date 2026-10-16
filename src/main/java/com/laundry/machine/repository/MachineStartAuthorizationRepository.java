package com.laundry.machine.repository;

import com.laundry.machine.entity.MachineStartAuthorization;
import com.laundry.machine.entity.MachineStartAuthorizationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link MachineStartAuthorization}.
 *
 * <p>Two concurrency primitives are used together:
 * <ol>
 *   <li><b>Unique partial index</b> {@code uq_msa_active_per_machine} —
 *       the database guarantees "at most one ACTIVE row per machine".</li>
 *   <li><b>{@code SELECT … FOR UPDATE}</b> via
 *       {@link LockModeType#PESSIMISTIC_WRITE} — used when transitioning
 *       {@code PENDING → GRANTED → START_PENDING → CONSUMED} so two ACK
 *       events from the same machine cannot race.</li>
 * </ol>
 */
@Repository
public interface MachineStartAuthorizationRepository
        extends JpaRepository<MachineStartAuthorization, UUID> {

    /**
     * Returns the currently ACTIVE authorization for the machine (if any).
     * Used by both {@code check-in} (dedupe) and the device-side
     * reconciliation path.
     */
    @Query("SELECT msa FROM MachineStartAuthorization msa " +
           "WHERE msa.machineId = :machineId " +
           "AND msa.status IN (com.laundry.machine.entity.MachineStartAuthorizationStatus.PENDING, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.GRANTED, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.START_PENDING) " +
           "ORDER BY msa.createdAt DESC")
    Optional<MachineStartAuthorization> findActiveByMachine(@Param("machineId") UUID machineId);

    /**
     * Same as {@link #findActiveByMachine(UUID)} but with a row lock so the
     * caller can mutate state safely in the same transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT msa FROM MachineStartAuthorization msa " +
           "WHERE msa.machineId = :machineId " +
           "AND msa.status IN (com.laundry.machine.entity.MachineStartAuthorizationStatus.PENDING, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.GRANTED, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.START_PENDING) " +
           "ORDER BY msa.createdAt DESC")
    Optional<MachineStartAuthorization> findActiveByMachineForUpdate(@Param("machineId") UUID machineId);

    /**
     * Most-recent active attempt by the same user on the same machine.
     * Used to rate-limit duplicate QR scans inside the refresh window.
     */
    @Query("SELECT msa FROM MachineStartAuthorization msa " +
           "WHERE msa.userId = :userId AND msa.machineId = :machineId " +
           "AND msa.status IN (com.laundry.machine.entity.MachineStartAuthorizationStatus.PENDING, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.GRANTED) " +
           "ORDER BY msa.createdAt DESC")
    List<MachineStartAuthorization> findRecentByUserAndMachine(@Param("userId") UUID userId,
                                                              @Param("machineId") UUID machineId);

    /**
     * Bulk-fetch every authorization whose {@code expiresAt} has passed but
     * which is still in an ACTIVE state — drives the expiry sweeper.
     */
    @Query("SELECT msa FROM MachineStartAuthorization msa " +
           "WHERE msa.status IN (com.laundry.machine.entity.MachineStartAuthorizationStatus.PENDING, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.GRANTED, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.START_PENDING) " +
           "AND msa.expiresAt <= :now " +
           "ORDER BY msa.expiresAt ASC")
    List<MachineStartAuthorization> findExpired(@Param("now") OffsetDateTime now);

    /**
     * Conditional bulk UPDATE used by the expiry sweeper. Returns the
     * number of rows actually transitioned. Atomic and idempotent — safe
     * to call from multiple scheduler instances.
     */
    @Modifying
    @Query("UPDATE MachineStartAuthorization msa " +
           "SET msa.status = com.laundry.machine.entity.MachineStartAuthorizationStatus.EXPIRED, " +
           "    msa.revokedAt = :now, " +
           "    msa.revokeReason = 'AUTO_EXPIRED' " +
           "WHERE msa.id IN :ids " +
           "AND msa.status IN (com.laundry.machine.entity.MachineStartAuthorizationStatus.PENDING, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.GRANTED, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.START_PENDING)")
    int markExpired(@Param("ids") List<UUID> ids,
                    @Param("now") OffsetDateTime now);

    /**
     * Revoke every active authorization for a reservation — fires when the
     * reservation is cancelled or refunded.
     */
    @Modifying
    @Query("UPDATE MachineStartAuthorization msa " +
           "SET msa.status = com.laundry.machine.entity.MachineStartAuthorizationStatus.REVOKED, " +
           "    msa.revokedAt = :now, " +
           "    msa.revokeReason = :reason " +
           "WHERE msa.reservationId = :reservationId " +
           "AND msa.status IN (com.laundry.machine.entity.MachineStartAuthorizationStatus.PENDING, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.GRANTED, " +
           "                    com.laundry.machine.entity.MachineStartAuthorizationStatus.START_PENDING)")
    int revokeByReservation(@Param("reservationId") UUID reservationId,
                            @Param("reason") String reason,
                            @Param("now") OffsetDateTime now);

    Optional<MachineStartAuthorization> findByIdAndUserId(UUID id, UUID userId);

    /** True once a reservation has been used for a wash (one cycle per booking). */
    /** Most recent admin test-run authorization for a machine (any status). */
    Optional<MachineStartAuthorization> findFirstByMachineIdAndTestRunTrueOrderByCreatedAtDesc(UUID machineId);

    boolean existsByReservationIdAndStatus(UUID reservationId, MachineStartAuthorizationStatus status);
}
