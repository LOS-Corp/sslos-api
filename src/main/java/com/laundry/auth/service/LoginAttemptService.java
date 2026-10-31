package com.laundry.auth.service;

import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Temporary lockout after repeated wrong passwords (FR-AUTH-01). After {@code maxFailures} failures
 * inside one {@code window}, the login key is locked for {@code lockout}. Failures are written in
 * their own transaction so they survive the failed login that caused them.
 */
@Slf4j
@Service
public class LoginAttemptService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int maxFailures;
    private final Duration window;
    private final Duration lockout;

    public LoginAttemptService(JdbcTemplate jdbc, Clock clock,
                               @Value("${app.auth.max-failed-logins:5}") int maxFailures,
                               @Value("${app.auth.failed-login-window:15m}") Duration window,
                               @Value("${app.auth.lockout-duration:15m}") Duration lockout) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.window = window;
        this.lockout = lockout;
    }

    /** Refuses the attempt while the key is locked (429, MSG03-style message). */
    @Transactional(readOnly = true)
    public void requireNotLocked(String loginKey) {
        List<Timestamp> lockedUntil = jdbc.queryForList(
                "SELECT locked_until FROM login_attempts WHERE login_key = ? AND locked_until > ?",
                Timestamp.class, loginKey, Timestamp.from(clock.instant()));
        if (!lockedUntil.isEmpty()) {
            throw BusinessException.of(ErrorCode.TOO_MANY_ATTEMPTS,
                    "Too many failed attempts. Please try again later.");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String loginKey) {
        Instant now = clock.instant();
        Timestamp nowTs = Timestamp.from(now);
        Timestamp windowStart = Timestamp.from(now.minus(window));
        // Start a new count when the previous window has passed; otherwise add one.
        jdbc.update("""
                INSERT INTO login_attempts (login_key, failures, first_failed_at)
                VALUES (?, 1, ?)
                ON CONFLICT (login_key) DO UPDATE SET
                    failures = CASE WHEN login_attempts.first_failed_at < ? THEN 1
                                    ELSE login_attempts.failures + 1 END,
                    first_failed_at = CASE WHEN login_attempts.first_failed_at < ? THEN EXCLUDED.first_failed_at
                                           ELSE login_attempts.first_failed_at END
                """, loginKey, nowTs, windowStart, windowStart);
        int locked = jdbc.update("""
                UPDATE login_attempts SET locked_until = ?
                WHERE login_key = ? AND failures >= ?
                """, Timestamp.from(now.plus(lockout)), loginKey, maxFailures);
        if (locked > 0) {
            log.warn("Login temporarily locked after {} failed attempts (key hash {})",
                    maxFailures, Integer.toHexString(loginKey.hashCode()));
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String loginKey) {
        jdbc.update("DELETE FROM login_attempts WHERE login_key = ?", loginKey);
    }
}
