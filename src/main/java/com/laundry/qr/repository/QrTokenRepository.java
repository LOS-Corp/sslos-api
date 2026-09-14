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

    @Query("SELECT q FROM QrToken q WHERE q.status = 'ACTIVE' AND q.expiresAt < :now")
    List<QrToken> findExpiredTokens(@Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE QrToken q SET q.status = 'EXPIRED' WHERE q.status = 'ACTIVE' AND q.expiresAt < :now")
    int expireOldTokens(@Param("now") LocalDateTime now);
}
