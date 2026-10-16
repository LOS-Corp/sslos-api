-- V35: Device provisioning + commissioning workflow.
--
-- A machine is sellable only after: a physical ESP32 is enrolled, paired to
-- the machine record, authenticated (HMAC challenge), issued its own EMQX
-- identity, passes a self-test and is commissioned by an admin.
--
-- Existing machines were wired manually before this workflow existed, so they
-- are grandfathered in as ACTIVE. New machines start UNASSIGNED.

ALTER TABLE machines
    ADD COLUMN IF NOT EXISTS deployment_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS device_id VARCHAR(64);

ALTER TABLE machines ALTER COLUMN deployment_status SET DEFAULT 'UNASSIGNED';

ALTER TABLE machines
    ADD CONSTRAINT chk_machine_deployment_status CHECK (deployment_status IN (
        'UNASSIGNED', 'PAIRING', 'PROVISIONED', 'TESTING',
        'TEST_FAILED', 'TEST_PASSED', 'ACTIVE'
    ));

CREATE UNIQUE INDEX IF NOT EXISTS uq_machines_device_id
    ON machines (device_id) WHERE device_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS iot_devices (
    id                        UUID          PRIMARY KEY,
    device_id                 VARCHAR(64)   NOT NULL UNIQUE,
    status                    VARCHAR(20)   NOT NULL,
    enrollment_version        INT           NOT NULL DEFAULT 1,
    credential_version        INT           NOT NULL DEFAULT 0,
    pairing_code_hash         VARCHAR(64),
    failed_pairing_attempts   INT           NOT NULL DEFAULT 0,
    machine_id                UUID          REFERENCES machines (id) ON DELETE SET NULL,
    mqtt_username             VARCHAR(64),
    challenge_nonce           VARCHAR(64),
    challenge_expires_at      TIMESTAMPTZ,
    firmware_version          VARCHAR(50),
    last_self_test_command_id VARCHAR(80),
    last_self_test_at         TIMESTAMPTZ,
    last_self_test_passed     BOOLEAN,
    last_self_test_detail     TEXT,
    enrolled_at               TIMESTAMPTZ   NOT NULL,
    activated_at              TIMESTAMPTZ,
    commissioned_at           TIMESTAMPTZ,
    commissioned_by           UUID,
    revoked_at                TIMESTAMPTZ,
    updated_at                TIMESTAMPTZ   NOT NULL,
    CONSTRAINT chk_iot_device_status CHECK (status IN (
        'UNCLAIMED', 'PAIRING', 'PROVISIONED', 'ACTIVE', 'LOCKED', 'REVOKED'
    ))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_iot_devices_machine
    ON iot_devices (machine_id) WHERE machine_id IS NOT NULL AND status <> 'REVOKED';

CREATE TABLE IF NOT EXISTS device_pairing_sessions (
    id            UUID          PRIMARY KEY,
    machine_id    UUID          NOT NULL REFERENCES machines (id) ON DELETE CASCADE,
    device_id     VARCHAR(64)   NOT NULL,
    status        VARCHAR(20)   NOT NULL,
    created_by    UUID,
    created_at    TIMESTAMPTZ   NOT NULL,
    expires_at    TIMESTAMPTZ   NOT NULL,
    completed_at  TIMESTAMPTZ,
    CONSTRAINT chk_pairing_session_status CHECK (status IN (
        'OPEN', 'COMPLETED', 'EXPIRED', 'CANCELLED'
    ))
);

CREATE INDEX IF NOT EXISTS idx_pairing_sessions_device
    ON device_pairing_sessions (device_id, status);

ALTER TABLE device_events
    DROP CONSTRAINT IF EXISTS chk_event_type;

ALTER TABLE device_events
    ADD CONSTRAINT chk_event_type CHECK (event_type IN (
        'STARTED', 'COMPLETED', 'STOP_COMMAND',
        'DOOR_OPENED', 'DOOR_CLOSED',
        'LEAK_DETECTED', 'OVERLOAD', 'EXCESSIVE_VIBRATION',
        'DEVICE_ERROR', 'CONNECTION_RECOVERED',
        'OFFLINE_DETECTED',
        'COMMAND_SENT', 'COMMAND_ACKNOWLEDGED', 'COMMAND_REJECTED',
        'COMMAND_TIMEOUT', 'STATUS_CHANGED', 'MACHINE_REGISTERED',
        'DEVICE_PAIRING_STARTED', 'DEVICE_PROVISIONED',
        'SELF_TEST_PASSED', 'SELF_TEST_FAILED',
        'MACHINE_COMMISSIONED', 'DEVICE_REVOKED'
    ));
