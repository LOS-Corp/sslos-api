-- V43: Failed login counter for temporary lockout (FR-AUTH-01: "too many failures → temporary lock and log").
-- Keyed by the normalized login identifier, so an unknown email is throttled the same way as a real one
-- and the response never reveals whether an account exists.

CREATE TABLE login_attempts (
    login_key       VARCHAR(254) PRIMARY KEY,
    failures        INT          NOT NULL DEFAULT 0 CHECK (failures >= 0),
    first_failed_at TIMESTAMPTZ  NOT NULL,
    locked_until    TIMESTAMPTZ
);
