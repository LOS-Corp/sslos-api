-- V50: evidence files (withdrawal transfer proofs UC-13 / BR-15, exception-refund evidence UC-08 4a).
-- Bytes live in the file store (app.storage.*); this table is the immutable index. Business rows refer
-- to a file as "attachment:<id>" inside their existing text columns (proof_reference, evidence).
CREATE TABLE attachments (
    id            UUID         PRIMARY KEY,
    purpose       VARCHAR(30)  NOT NULL CHECK (purpose IN ('WITHDRAWAL_PROOF', 'REFUND_EVIDENCE')),
    file_name     VARCHAR(200) NOT NULL,
    content_type  VARCHAR(100) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    sha256        CHAR(64)     NOT NULL,
    storage_key   VARCHAR(200) NOT NULL UNIQUE,
    uploaded_by   UUID         NOT NULL REFERENCES users (id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_attachments_uploader ON attachments (uploaded_by, created_at DESC);

-- Evidence must not change after the fact (NFR-14), same rule as audit_logs.
CREATE TRIGGER trg_attachments_append_only
    BEFORE UPDATE OR DELETE ON attachments
    FOR EACH ROW EXECUTE FUNCTION reject_mutation_append_only();
