package com.laundry.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :at where t.userId = :userId and t.revokedAt is null")
    int revokeAll(UUID userId, Instant at);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :at where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(UUID familyId, Instant at);
}
