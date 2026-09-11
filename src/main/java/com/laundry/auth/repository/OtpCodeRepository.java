package com.laundry.auth.repository;

import com.laundry.auth.entity.OtpCode;
import com.laundry.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OtpCodeRepository extends JpaRepository<OtpCode, UUID> {

    /**
     * Find a valid OTP code for a user.
     * Valid means: not used and not expired.
     */
    @Query("SELECT o FROM OtpCode o WHERE o.user = :user AND o.code = :code AND o.used = false AND o.expiresAt > CURRENT_TIMESTAMP")
    Optional<OtpCode> findValidOtpByUserAndCode(@Param("user") User user, @Param("code") String code);

    /**
     * Find the most recent valid OTP for a user (for rate limiting).
     */
    @Query("SELECT o FROM OtpCode o WHERE o.user = :user AND o.used = false AND o.expiresAt > CURRENT_TIMESTAMP ORDER BY o.createdAt DESC LIMIT 1")
    Optional<OtpCode> findMostRecentValidOtp(@Param("user") User user);

    /**
     * Invalidate all existing OTP codes for a user.
     */
    @Modifying
    @Query("UPDATE OtpCode o SET o.used = true WHERE o.user = :user AND o.used = false")
    void invalidateAllByUser(@Param("user") User user);

    /**
     * Delete all expired OTP codes (cleanup).
     */
    @Modifying
    @Query("DELETE FROM OtpCode o WHERE o.expiresAt < CURRENT_TIMESTAMP")
    void deleteExpiredCodes();
}
