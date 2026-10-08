package com.laundry.qr.repository;

import com.laundry.qr.entity.QrToken;
import com.laundry.qr.entity.QrTokenStatus;
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
 * QR Token repository
 */
@Repository
public interface QrTokenRepository extends JpaRepository<QrToken, UUID> {

    Optional<QrToken> findByToken(String token);

    List<QrToken> findByReferenceIdAndReferenceType(UUID referenceId, String referenceType);

    List<QrToken> findByStatus(QrTokenStatus status);

    /**
     * Legacy read — retained for callers that need the full row.
     */
    @Query("SELECT q FROM QrToken q WHERE q.status = 'ACTIVE' AND q.expiresAt < :now")
    List<QrToken> findExpiredTokens(@Param("now") LocalDateTime now);

    /**
     * Bulk UPDATE: flip ACTIVE → EXPIRED for LOCKER tokens whose deadline has passed.
     *
     * <p>Important: the type filter explicitly EXCLUDES {@code MACHINE_ACTIVATION}.
     * Static machine QR codes use {@code GET /api/machines/{machineCode}/qr} and do
     * not have an expiry; any historical MACHINE_ACTIVATION tokens still in the DB
     * are not touched here because the QR contract is "deterministic URL, no token".
     */
    @Modifying
    @Query("UPDATE QrToken q SET q.status = 'EXPIRED' " +
           "WHERE q.status = 'ACTIVE' AND q.expiresAt < :now " +
           "AND q.type IN (com.laundry.qr.entity.QrTokenType.LOCKER_DROP_OFF, " +
           "               com.laundry.qr.entity.QrTokenType.LOCKER_PICKUP)")
    int expireLockerTokens(@Param("now") LocalDateTime now);

    /**
     * Backward-compatible wrapper that retains the original method signature.
     * Delegates to {@link #expireLockerTokens} which excludes MACHINE_ACTIVATION.
     */
    @Deprecated
    default int expireOldTokens(LocalDateTime now) {
        return expireLockerTokens(now);
    }
}