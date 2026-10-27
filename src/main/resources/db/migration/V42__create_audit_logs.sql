-- V42: Audit trail of sensitive actions — money, permissions, devices, order state (NFR-14, BR-25, SRS 5.2).
-- Append-only: a trigger refuses UPDATE and DELETE so history cannot be rewritten by application code.
-- actor_id has no foreign key on purpose: entries outlive the accounts that produced them.

CREATE TABLE audit_logs (
    id           BIGSERIAL PRIMARY KEY,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id     UUID,
    actor_role   VARCHAR(20),
    action       VARCHAR(60) NOT NULL,
    entity_type  VARCHAR(40) NOT NULL,
    entity_id    VARCHAR(80),
    branch_id    UUID,
    reason       VARCHAR(500),
    before_state JSONB,
    after_state  JSONB
);

CREATE INDEX idx_audit_logs_occurred_at ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_logs_branch ON audit_logs (branch_id, occurred_at DESC);
CREATE INDEX idx_audit_logs_entity ON audit_logs (entity_type, entity_id);

CREATE FUNCTION reject_mutation_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION reject_mutation_append_only();
