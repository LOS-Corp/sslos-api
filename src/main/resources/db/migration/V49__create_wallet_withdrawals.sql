-- V49: Withdrawal of wallet credit to a bank account (UC-13, FR-WAL-03, BR-15, SC-30).
-- The amount is held in the ledger (WITHDRAWAL_HOLD) as soon as the request is made; the Owner transfers
-- it manually and the request is COMPLETED only with a transfer proof. A rejection releases the hold.

CREATE TABLE wallet_withdrawals (
    id               UUID         PRIMARY KEY,
    customer_id      UUID         NOT NULL REFERENCES users (id),
    amount_vnd       BIGINT       NOT NULL CHECK (amount_vnd > 0),
    bank_name        VARCHAR(100) NOT NULL,
    account_number   VARCHAR(30)  NOT NULL,
    account_holder   VARCHAR(120) NOT NULL,
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED', 'REJECTED')),
    proof_reference  VARCHAR(300),
    rejection_reason VARCHAR(500),
    decided_by       UUID,
    requested_at     TIMESTAMPTZ  NOT NULL,
    decided_at       TIMESTAMPTZ,
    -- APPROVED is not COMPLETED: completion always carries the proof (BR-15).
    CONSTRAINT chk_withdrawal_completed_has_proof
        CHECK (status <> 'COMPLETED' OR (proof_reference IS NOT NULL AND decided_by IS NOT NULL))
);

CREATE INDEX idx_wallet_withdrawals_status ON wallet_withdrawals (status, requested_at);
CREATE INDEX idx_wallet_withdrawals_customer ON wallet_withdrawals (customer_id, requested_at DESC);
