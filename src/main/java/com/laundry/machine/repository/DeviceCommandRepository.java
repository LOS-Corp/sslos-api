package com.laundry.machine.repository;

import com.laundry.machine.entity.DeviceCommand;
import com.laundry.machine.entity.DeviceCommandStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for DeviceCommand entities.
 *
 * <p>Optimization notes:
 * <ul>
 *   <li>{@link #findStaleSentCommandIds} — projection of UUIDs only; used by the
 *       timeout scheduler to avoid loading full entities per candidate. Bounded
 *       by a {@link Pageable} so memory/lock footprint per tick is predictable.</li>
 *   <li>{@link #markSentAsTimedOut} — single conditional bulk UPDATE that flips
 *       SENT → TIMEOUT for rows whose deadline has passed. Returns the number of
 *       rows actually updated, which protects against late ACKs (a row whose
 *       status was flipped to ACKNOWLEDGED in the race window is skipped).</li>
 *   <li>{@link #findTimedOutBatchMetadata} — narrow projection used after the
 *       bulk UPDATE to build the event payload without re-reading every column.</li>
 * </ul>
 */
@Repository
public interface DeviceCommandRepository extends JpaRepository<DeviceCommand, UUID> {

    Optional<DeviceCommand> findByCommandId(String commandId);

    Page<DeviceCommand> findByMachineIdOrderByCreatedAtDesc(UUID machineId, Pageable pageable);

    /**
     * Legacy read — kept for callers that need the full entity (e.g., admin views).
     * The timeout scheduler no longer uses this; it uses {@link #findStaleSentCommandIds}
     * (ID projection) + {@link #markSentAsTimedOut} (bulk UPDATE).
     */
    List<DeviceCommand> findByStatusAndTimeoutAtBefore(
            DeviceCommandStatus status, OffsetDateTime now);

    /**
     * Projection: return only IDs of SENT commands whose deadline has passed.
     * Bounded by the caller-supplied {@link Pageable} (use {@link PageRequest#ofSize(int)}
     * with the configured {@code commandTimeoutBatchSize}) so memory/lock footprint per
     * scheduler tick is predictable.
     */
    @Query(value = "SELECT d.id FROM DeviceCommand d " +
                   "WHERE d.status = com.laundry.machine.entity.DeviceCommandStatus.SENT " +
                   "AND d.timeoutAt < :now " +
                   "ORDER BY d.timeoutAt ASC",
           countQuery = "SELECT COUNT(d.id) FROM DeviceCommand d " +
                        "WHERE d.status = com.laundry.machine.entity.DeviceCommandStatus.SENT " +
                        "AND d.timeoutAt < :now")
    Page<UUID> findStaleSentCommandIdsPage(@Param("now") OffsetDateTime now, Pageable pageable);

    /**
     * Convenience wrapper used by the scheduler — returns the IDs in a fixed-size
     * window (oldest deadlines first) capped at {@code batchSize}.
     */
    default List<UUID> findStaleSentCommandIds(OffsetDateTime now, int batchSize) {
        return findStaleSentCommandIdsPage(now, PageRequest.ofSize(Math.max(1, batchSize))).getContent();
    }

    /**
     * Conditional bulk UPDATE: SENT → TIMEOUT only for rows whose status is
     * still SENT and whose deadline has passed.
     *
     * <p>Concurrency: if an ACK arrives between {@link #findStaleSentCommandIds}
     * and this UPDATE, the corresponding row is no longer in SENT status and is
     * left untouched. We do NOT clobber a late ACK. The rowcount returned is the
     * authoritative number of commands that actually transitioned to TIMEOUT.
     */
    @Modifying
    @Query("UPDATE DeviceCommand d SET d.status = :newStatus, d.updatedAt = :now " +
           "WHERE d.id IN :ids AND d.status = com.laundry.machine.entity.DeviceCommandStatus.SENT " +
           "AND d.timeoutAt < :now")
    int markSentAsTimedOut(@Param("ids") List<UUID> ids,
                            @Param("newStatus") DeviceCommandStatus newStatus,
                            @Param("now") OffsetDateTime now);

    /**
     * Read minimal metadata for commands that were just flipped to TIMEOUT in
     * the same transaction window. Used to build the event payload without
     * touching columns we don't need.
     *
     * <p>Note: keys off the {@code id IN :ids} list (provided by the caller)
     * and {@code status = TIMEOUT}. We cannot use updated_at as a discriminator
     * because bulk UPDATEs share a single timestamp across all rows.
     */
    @Query("SELECT d FROM DeviceCommand d " +
           "WHERE d.status = com.laundry.machine.entity.DeviceCommandStatus.TIMEOUT " +
           "AND d.id IN :ids")
    List<DeviceCommand> findTimedOutBatchMetadata(@Param("ids") List<UUID> ids);

    /**
     * Legacy per-row conditional UPDATE. Retained for callers (e.g., tests) that
     * want fine-grained row-by-row updates. The bulk {@link #markSentAsTimedOut}
     * is preferred in production schedulers.
     */
    @Modifying
    @Query("UPDATE DeviceCommand d SET d.status = 'TIMEOUT', d.updatedAt = :now " +
           "WHERE d.id = :id AND d.status = 'SENT'")
    int updateStatusToTimeout(@Param("id") UUID id, @Param("now") OffsetDateTime now);

    /** Check for existing PENDING/SENT command on the same machine (dedup guard). */
    boolean existsByMachineIdAndStatusIn(UUID machineId, List<DeviceCommandStatus> statuses);

    List<DeviceCommand> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);
}