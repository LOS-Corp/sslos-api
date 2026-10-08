-- V29: Create machine_start_authorizations table.
--
-- A short-lived, single-use contract that lets a customer who has a
-- CONFIRMED/ACTIVE reservation unlock the physical START button of a
-- specific IoT machine.
--
-- Concurrency:
--   * Only ONE ACTIVE row (status IN (PENDING, GRANTED, START_PENDING))
--     per machine is allowed at any time → enforced via the partial
--     unique index `uq_msa_active_per_machine`.
--   * State transitions use optimistic locking (version column).

CREATE TABLE IF NOT EXISTS machine_start_authorizations (
    id                  UUID            PRIMARY KEY,
    reservation_id      UUID            NOT NULL,
    transaction_id      UUID,
    user_id             UUID            NOT NULL,
    machine_id          UUID            NOT NULL,
    machine_code        VARCHAR(50)     NOT NULL,
    status              VARCHAR(20)     NOT NULL,
    expires_at          TIMESTAMPTZ     NOT NULL,
    created_at          TIMESTAMPTZ     NOT NULL,
    granted_at          TIMESTAMPTZ,
    consumed_at         TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    revoke_reason       VARCHAR(200),
    device_boot_id      VARCHAR(100),
    run_id              UUID,
    version             BIGINT          NOT NULL DEFAULT 0,

    CONSTRAINT chk_msa_status CHECK (status IN (
        'PENDING', 'GRANTED', 'START_PENDING',
        'CONSUMED', 'EXPIRED', 'REVOKED'
    ))
);

-- General lookup indexes.
CREATE INDEX IF NOT EXISTS idx_msa_machine        ON machine_start_authorizations (machine_id);
CREATE INDEX IF NOT EXISTS idx_msa_user          ON machine_start_authorizations (user_id);
CREATE INDEX IF NOT EXISTS idx_msa_reservation   ON machine_start_authorizations (reservation_id);
CREATE INDEX IF NOT EXISTS idx_msa_status_expires ON machine_start_authorizations (status, expires_at);
CREATE INDEX IF NOT EXISTS idx_msa_boot          ON machine_start_authorizations (machine_id, device_boot_id);

-- At most one ACTIVE authorization per machine.
CREATE UNIQUE INDEX IF NOT EXISTS uq_msa_active_per_machine
    ON machine_start_authorizations (machine_id)
    WHERE status IN ('PENDING', 'GRANTED', 'START_PENDING');

-- Helpful for the "latest for user/machine" lookup used by the QR check-in
-- dedupe rule.
CREATE INDEX IF NOT EXISTS idx_msa_user_machine_created
    ON machine_start_authorizations (user_id, machine_id, created_at DESC);
