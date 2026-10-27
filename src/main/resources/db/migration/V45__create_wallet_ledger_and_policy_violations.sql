-- V45: In-chain credit wallet with an immutable ledger (BR-15, FR-WAL-01, NFR-17) and the rolling
-- 90-day violation history used by the refund policy (BR-12, FR-REF-02/03). Money is integer VND (SRS 5.2).

CREATE TABLE wallets (
    id          UUID        PRIMARY KEY,
    customer_id UUID        NOT NULL UNIQUE REFERENCES users (id),
    balance_vnd BIGINT      NOT NULL DEFAULT 0 CHECK (balance_vnd >= 0),   -- never negative (BR-15)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version     BIGINT      NOT NULL DEFAULT 0
);

-- balance_vnd is a cache of SUM(amount_vnd); the daily reconciliation job compares both (NFR-17).
CREATE TABLE wallet_entries (
    id          BIGSERIAL    PRIMARY KEY,
    wallet_id   UUID         NOT NULL REFERENCES wallets (id),
    type        VARCHAR(30)  NOT NULL CHECK (type IN (
                    'REFUND_CREDIT', 'LATE_PAYMENT_CREDIT', 'PAYMENT_DEBIT', 'PAYMENT_REVERSAL',
                    'WITHDRAWAL_HOLD', 'WITHDRAWAL_RELEASE', 'STORAGE_FEE')),
    amount_vnd  BIGINT       NOT NULL CHECK (amount_vnd <> 0),           -- positive in, negative out
    reference   VARCHAR(80)  NOT NULL,                                   -- e.g. "refund:{id}", "booking:{id}"
    description VARCHAR(200),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- The same business event can never move money twice (NFR-06), even if a webhook or job repeats.
    CONSTRAINT uq_wallet_entry_event UNIQUE (wallet_id, type, reference)
);

CREATE INDEX idx_wallet_entries_wallet_time ON wallet_entries (wallet_id, created_at DESC);

CREATE TRIGGER trg_wallet_entries_append_only
    BEFORE UPDATE OR DELETE ON wallet_entries
    FOR EACH ROW EXECUTE FUNCTION reject_mutation_append_only();

-- One row per customer-caused LATE_CANCELLATION or NO_SHOW; one order yields at most one event (BR-12).
CREATE TABLE policy_violations (
    id          BIGSERIAL   PRIMARY KEY,
    customer_id UUID        NOT NULL REFERENCES users (id),
    booking_id  UUID        NOT NULL UNIQUE REFERENCES scheduled_bookings (id),
    type        VARCHAR(30) NOT NULL CHECK (type IN ('LATE_CANCELLATION', 'NO_SHOW')),
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_policy_violations_customer_time ON policy_violations (customer_id, occurred_at);
