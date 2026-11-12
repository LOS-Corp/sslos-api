-- V57: Boards talk to the backend over USB serial instead of MQTT.
--
-- The store server has the boards plugged in. A board says HELLO with its
-- deviceId; the first HELLO creates an UNCLAIMED row, an admin approves it
-- (VERIFIED) and the owner attaches it to a machine (PROVISIONED). There is no
-- broker, no per-device MQTT credential, no setup code and no pairing session.

-- Unfinished pairings from the QR flow cannot complete any more.
UPDATE iot_devices SET status = 'UNCLAIMED' WHERE status = 'PAIRING';
UPDATE machines SET deployment_status = 'UNASSIGNED'
WHERE deployment_status = 'PAIRING' AND device_id IS NULL;

DROP TABLE IF EXISTS device_pairing_sessions;

ALTER TABLE iot_devices
    DROP COLUMN IF EXISTS credential_version,
    DROP COLUMN IF EXISTS setup_key_enc,
    DROP COLUMN IF EXISTS failed_pairing_attempts,
    DROP COLUMN IF EXISTS mqtt_username,
    DROP COLUMN IF EXISTS challenge_nonce,
    DROP COLUMN IF EXISTS challenge_expires_at;

ALTER TABLE iot_devices
    ADD COLUMN IF NOT EXISTS device_type  VARCHAR(20),
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS verified_by  UUID;

ALTER TABLE iot_devices DROP CONSTRAINT IF EXISTS chk_iot_device_type;
ALTER TABLE iot_devices
    ADD CONSTRAINT chk_iot_device_type CHECK (device_type IS NULL OR device_type IN ('WASHER', 'DRYER', 'LOCKER'));

ALTER TABLE iot_devices DROP CONSTRAINT IF EXISTS chk_iot_device_status;
ALTER TABLE iot_devices
    ADD CONSTRAINT chk_iot_device_status CHECK (status IN (
        'UNCLAIMED', 'VERIFIED', 'PROVISIONED', 'ACTIVE', 'REVOKED'
    ));

ALTER TABLE machines DROP CONSTRAINT IF EXISTS chk_machine_deployment_status;
ALTER TABLE machines
    ADD CONSTRAINT chk_machine_deployment_status CHECK (deployment_status IN (
        'UNASSIGNED', 'PROVISIONED', 'TESTING',
        'TEST_FAILED', 'TEST_PASSED', 'ACTIVE', 'MAINTENANCE'
    ));
