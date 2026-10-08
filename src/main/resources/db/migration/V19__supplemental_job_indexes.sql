-- V19: Supplemental indexes for scheduled-job query performance
--
-- All indexes are CREATE INDEX IF NOT EXISTS so this migration is idempotent.
-- Partial indexes (WHERE clause) are preferred where the query always filters
-- on the same constant value, so only relevant rows are indexed.

-- ── Machines: offline detector ─────────────────────────────────────────────
-- detectOfflineMachines() queries:
--   WHERE online = true AND last_heartbeat_at < :threshold
-- Partial index on only online=true rows avoids indexing OFFLINE machines.
CREATE INDEX IF NOT EXISTS idx_machines_online_heartbeat
    ON machines (last_heartbeat_at)
    WHERE online = true;

-- ── Transactions: expiration job ───────────────────────────────────────────
-- expireOldTransactions() queries:
--   WHERE status = 'PENDING_PAYMENT' AND expires_at < now
-- Partial index on only PENDING_PAYMENT rows.
CREATE INDEX IF NOT EXISTS idx_transactions_pending_expires
    ON transactions (expires_at)
    WHERE status = 'PENDING_PAYMENT';

-- ── Machine reservations: overlap-check queries ────────────────────────────
-- MachineReservationService.checkMachineAvailability() queries overlapping
-- reservations on the same machine (RESERVED/CONFIRMED/ACTIVE).
-- Partial index on active reservation states only.
CREATE INDEX IF NOT EXISTS idx_reservations_active_machine_time
    ON machine_reservations (machine_id, start_time, end_time)
    WHERE status IN ('RESERVED', 'CONFIRMED', 'ACTIVE');

-- ── Device commands: timeout scheduler ─────────────────────────────────────
-- detectCommandTimeouts() queries:
--   WHERE status = 'SENT' AND timeout_at < now
-- Partial index on only SENT rows.
CREATE INDEX IF NOT EXISTS idx_device_cmd_sent_timeout
    ON device_commands (timeout_at)
    WHERE status = 'SENT';
