-- V20: Refinement for QR token expiration scheduler
--
-- The QR expiration scheduler now filters:
--   WHERE status = 'ACTIVE' AND expires_at < :now
--     AND type IN ('LOCKER_DROP_OFF', 'LOCKER_PICKUP')
--
-- The previous general index (idx_qr_tokens_expires on expires_at) covers the
-- ORDER BY, but a partial index keyed by (status, type) gives Postgres a smaller
-- candidate set and avoids scanning USED/REVOKED/EXPIRED rows on every tick.
--
-- Idempotent: uses IF NOT EXISTS so the migration is safe to re-run.

CREATE INDEX IF NOT EXISTS idx_qr_tokens_active_locker_expires
    ON qr_tokens (expires_at)
    WHERE status = 'ACTIVE'
      AND type IN ('LOCKER_DROP_OFF', 'LOCKER_PICKUP');