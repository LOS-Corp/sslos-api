-- V56: An IoT device can be added before its machine.
--
-- The admin scans the board's 6-digit code on the IoT page; the board proves it
-- holds the code and becomes VERIFIED (no machine, no MQTT credential yet). The
-- encrypted code is kept until the board is attached to a machine, so the admin
-- can later pick it from a list instead of scanning again.
--
-- A pairing session without a machine is such a device-first verification.

ALTER TABLE device_pairing_sessions ALTER COLUMN machine_id DROP NOT NULL;

ALTER TABLE iot_devices ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ;

ALTER TABLE iot_devices DROP CONSTRAINT IF EXISTS chk_iot_device_status;
ALTER TABLE iot_devices
    ADD CONSTRAINT chk_iot_device_status CHECK (status IN (
        'UNCLAIMED', 'PAIRING', 'VERIFIED', 'PROVISIONED', 'ACTIVE', 'REVOKED'
    ));
