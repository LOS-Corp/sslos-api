-- V27: Persist latest runtime snapshot per machine.
--
-- High-frequency MQTT telemetry (weightKg, vibrationG, phase, cycleActive, …)
-- is no longer written to the `machines` table by Hibernate — that produced a
-- database write storm every 2–5 s per device.
--
-- Instead, MachineRuntimeStateService keeps an in-memory map keyed by
-- machines.id and flushes it here as a single JSONB row per machine. This:
--   * survives a JVM restart so frontend "Machine Detail" still shows the
--     last known sensor values after a backend restart.
--   * is one bulk UPSERT per flush interval (default 10 s) instead of one
--     UPDATE per MQTT message.
--
-- Schema:
--   machine_id          -- PK; UUID; mirrors machines.id
--   snapshot            -- JSONB; opaque envelope, JSON-deserialized on demand
--   updated_at          -- last write timestamp (UTC)

CREATE TABLE IF NOT EXISTS machine_runtime_state (
    machine_id  UUID PRIMARY KEY,
    snapshot    JSONB NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_machine_runtime_state_updated_at
    ON machine_runtime_state (updated_at DESC);