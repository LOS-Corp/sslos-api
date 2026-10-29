package com.laundry.shared.scheduling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Makes a scheduled job run on one backend instance at a time (ADR-09, UC-28) with PostgreSQL
 * advisory locks — no extra table or library. An instance that does not get the lock skips the
 * tick; jobs stay idempotent, so a skipped tick is simply done by the next one.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobLock {

    private static final String TRY_XACT_LOCK = "SELECT pg_try_advisory_xact_lock(hashtext(?))";
    private static final String TRY_SESSION_LOCK = "SELECT pg_try_advisory_lock(hashtext(?))";
    private static final String SESSION_UNLOCK = "SELECT pg_advisory_unlock(hashtext(?))";
    private static final String PREFIX = "job:";

    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    /**
     * For a job that already runs in a transaction: true if this instance owns the job until the
     * transaction ends (the lock is released automatically on commit or rollback).
     */
    public boolean tryLockForTransaction(String job) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("tryLockForTransaction needs an active transaction: " + job);
        }
        boolean acquired = Boolean.TRUE.equals(jdbc.queryForObject(TRY_XACT_LOCK, Boolean.class, PREFIX + job));
        if (!acquired) {
            log.debug("Job {} is running on another instance; skipping this tick", job);
        }
        return acquired;
    }

    /** For a job without its own transaction: runs {@code task} only if no other instance is running it. */
    public void runExclusive(String job, Runnable task) {
        try (Connection connection = dataSource.getConnection()) {
            if (!call(connection, TRY_SESSION_LOCK, job)) {
                log.debug("Job {} is running on another instance; skipping this tick", job);
                return;
            }
            try {
                task.run();
            } finally {
                call(connection, SESSION_UNLOCK, job);
            }
        } catch (SQLException e) {
            log.error("Job {} skipped: could not take its lock", job, e);
        }
    }

    private static boolean call(Connection connection, String sql, String job) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PREFIX + job);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
