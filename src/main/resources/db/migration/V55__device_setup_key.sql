-- V55: Device-generated setup key replaces workshop enrollment.
--
-- The ESP32 now generates its own random setup key on first boot and shows it
-- as a QR on its screen. The admin scans that QR while adding the machine; the
-- backend keeps the key AES-GCM encrypted for the pairing window only, and the
-- board proves possession with HMAC(setupKey, deviceId:nonce).
--
-- This removes the server-issued claim secret (enrollment_version), the label
-- pairing code (pairing_code_hash) and the LOCKED state.

ALTER TABLE iot_devices
    ADD COLUMN IF NOT EXISTS setup_key_enc TEXT;

ALTER TABLE iot_devices
    DROP COLUMN IF EXISTS pairing_code_hash,
    DROP COLUMN IF EXISTS enrollment_version;

UPDATE iot_devices SET status = 'REVOKED', revoked_at = COALESCE(revoked_at, NOW())
WHERE status = 'LOCKED';

ALTER TABLE iot_devices DROP CONSTRAINT IF EXISTS chk_iot_device_status;
ALTER TABLE iot_devices
    ADD CONSTRAINT chk_iot_device_status CHECK (status IN (
        'UNCLAIMED', 'PAIRING', 'PROVISIONED', 'ACTIVE', 'REVOKED'
    ));
