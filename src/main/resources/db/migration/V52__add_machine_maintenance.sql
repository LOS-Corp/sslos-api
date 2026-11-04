-- V52: MAINTENANCE deployment status (hardware repair → mandatory self-test → ACTIVE).
-- Existing rows keep their status; maintenance_since is NULL outside a repair.

ALTER TABLE machines
    ADD COLUMN IF NOT EXISTS maintenance_since TIMESTAMPTZ;

ALTER TABLE machines
    DROP CONSTRAINT IF EXISTS chk_machine_deployment_status;

ALTER TABLE machines
    ADD CONSTRAINT chk_machine_deployment_status CHECK (deployment_status IN (
        'UNASSIGNED', 'PAIRING', 'PROVISIONED', 'TESTING',
        'TEST_FAILED', 'TEST_PASSED', 'ACTIVE', 'MAINTENANCE'
    ));

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
        'MACHINE_COMMISSIONED', 'DEVICE_REVOKED',
        'MAINTENANCE_STARTED', 'MAINTENANCE_COMPLETED'
    ));
