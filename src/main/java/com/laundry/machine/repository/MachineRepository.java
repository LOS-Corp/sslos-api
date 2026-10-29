package com.laundry.machine.repository;

import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
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
 * Repository for Machine entities.
 *
 * <p>Machine identity is scoped by {@code (branchId, machineCode)} where
 * {@code branchId} is a {@link UUID}. All MQTT topic lookups use this composite key.
 */
@Repository
public interface MachineRepository extends JpaRepository<Machine, UUID> {

    /** Lookup by MQTT topic segment (e.g., "WM001"). */
    Optional<Machine> findByMachineCode(String machineCode);

    boolean existsByMachineCode(String machineCode);

    /** Multi-branch lookup by branchId and machineCode. */
    Optional<Machine> findByBranchIdAndMachineCode(UUID branchId, String machineCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Machine m WHERE m.id = :id")
    Optional<Machine> findByIdForReservation(@Param("id") UUID id);

    @Query(value = """
            SELECT EXISTS (
                SELECT 1
                FROM booking_resource_allocations a
                JOIN scheduled_bookings b ON b.id = a.booking_id
                WHERE a.resource_id = :machineId
                  AND (b.status IN ('CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING', 'READY_FOR_PICKUP')
                       OR (b.status = 'HELD' AND b.hold_expires_at > NOW()))
            )
            """, nativeQuery = true)
    boolean hasActiveBookingAllocation(@Param("machineId") UUID machineId);

    boolean existsByBranchIdAndMachineCode(UUID branchId, String machineCode);

    List<Machine> findByBranchId(UUID branchId);

    List<Machine> findByStatus(MachineStatus status);

    List<Machine> findByOnlineTrue();

    /**
     * Full-entity variant — retained for callers that want the whole row.
     */
    @Query("SELECT m FROM Machine m WHERE m.online = true AND " +
           "(m.lastHeartbeatAt IS NULL OR m.lastHeartbeatAt < :threshold)")
    List<Machine> findStaleOnlineMachines(@Param("threshold") OffsetDateTime threshold);

    /**
     * Projection: return only IDs of machines marked online whose last_heartbeat_at
     * is older than {@code threshold} (or NULL). Bounded by a {@link Pageable} so
     * memory/lock footprint per scheduler tick is predictable.
     */
    @Query(value = "SELECT id FROM machines " +
                   "WHERE online = true AND (last_heartbeat_at IS NULL OR last_heartbeat_at < :threshold) " +
                   "ORDER BY last_heartbeat_at ASC NULLS FIRST",
           nativeQuery = true)
    Page<UUID> findStaleOnlineMachineIdsPage(@Param("threshold") OffsetDateTime threshold, Pageable pageable);

    /**
     * Convenience wrapper used by the scheduler — returns the IDs in a fixed-size
     * window (oldest heartbeats first) capped at {@code batchSize}.
     */
    default List<UUID> findStaleOnlineMachineIds(OffsetDateTime threshold, int batchSize) {
        return findStaleOnlineMachineIdsPage(threshold, PageRequest.ofSize(Math.max(1, batchSize))).getContent();
    }

    /**
     * Efficiently update heartbeat fields without loading the full entity.
     */
    @Modifying
    @Query("UPDATE Machine m SET m.online = true, m.lastHeartbeatAt = :ts, " +
           "m.updatedAt = :ts WHERE m.id = :id")
    int updateHeartbeat(@Param("id") UUID id, @Param("ts") OffsetDateTime ts);

    /**
     * Mark machines offline (unconditional bulk UPDATE).
     */
    @Modifying
    @Query("UPDATE Machine m SET m.online = false, m.updatedAt = :ts WHERE m.id IN :ids")
    int markOffline(@Param("ids") List<UUID> ids, @Param("ts") OffsetDateTime ts);

    /**
     * Conditional bulk UPDATE: flip online → false ONLY for machines that are
     * currently marked online AND whose {@code last_heartbeat_at} is older than
     * {@code threshold}.
     */
    @Modifying
    @Query("UPDATE Machine m SET m.online = false, m.updatedAt = :ts " +
           "WHERE m.id IN :ids AND m.online = true " +
           "AND (m.lastHeartbeatAt IS NULL OR m.lastHeartbeatAt < :threshold)")
    int markOfflineIfStale(@Param("ids") List<UUID> ids,
                           @Param("ts") OffsetDateTime ts,
                           @Param("threshold") OffsetDateTime threshold);

    // ── Runtime snapshot persistence (V27) ───────────────────────────────────

    /**
     * UPSERT the JSONB runtime snapshot for a single machine.
     * Runs as a single statement so the periodic flusher can flush
     * N machines with N statements instead of N round-trips.
     *
     * <p>Called by {@link com.laundry.machine.runtime.MachineRuntimeStateService}
     * — do NOT call from the MQTT hot path.
     */
    @Modifying
    @Query(value = """
            INSERT INTO machine_runtime_state (machine_id, snapshot, updated_at)
            VALUES (:machineId, CAST(:snapshot AS JSONB), NOW())
            ON CONFLICT (machine_id) DO UPDATE
              SET snapshot   = EXCLUDED.snapshot,
                  updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    int upsertRuntimeSnapshot(@Param("machineId") UUID machineId,
                              @Param("snapshot") String snapshotJson);

    /**
     * Read the persisted snapshot JSON for a machine.
     */
    @Query(value = "SELECT snapshot::text FROM machine_runtime_state WHERE machine_id = :machineId",
            nativeQuery = true)
    Optional<String> findRuntimeSnapshot(@Param("machineId") UUID machineId);

    /**
     * Delete the persisted snapshot (called when a machine is hard-deleted).
     */
    @Modifying
    @Query(value = "DELETE FROM machine_runtime_state WHERE machine_id = :machineId",
            nativeQuery = true)
    int deleteRuntimeSnapshot(@Param("machineId") UUID machineId);

    // ── Admin search ────────────────────────────────────────────────

    /**
     * Admin search: free-text q + status + type + branchId, with paging.
     */
    @Query("SELECT m FROM Machine m WHERE " +
           "(:q IS NULL OR :q = '' OR " +
           "  LOWER(m.machineCode) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "  LOWER(COALESCE(m.displayName, '')) LIKE LOWER(CONCAT('%', :q, '%'))) AND " +
           "(:status IS NULL OR m.status = :status) AND " +
           "(:type IS NULL OR m.type = :type) AND " +
           "(:branchId IS NULL OR m.branchId = :branchId)")
    Page<Machine> searchAdmin(@Param("q") String q,
                             @Param("status") MachineStatus status,
                             @Param("type") MachineType type,
                             @Param("branchId") UUID branchId,
                             Pageable pageable);
}