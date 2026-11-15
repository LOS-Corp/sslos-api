-- V59: Boards can also reach the backend over the store's Wi-Fi through the local Mosquitto broker.
--
-- A USB board is trusted because it is physically plugged into the store server. A Wi-Fi board is
-- not, so each board keeps an ECDSA P-256 key pair in its flash (the private key never leaves it)
-- and signs a fresh nonce on every connection. The backend pins the public key the first time an
-- unapproved board connects; the admin compares its fingerprint with the one on the board's screen
-- before approving. A different key later is refused and parked as "pending" until an admin trusts it.

ALTER TABLE iot_devices
    ADD COLUMN IF NOT EXISTS public_key              TEXT,
    ADD COLUMN IF NOT EXISTS key_fingerprint         VARCHAR(64),
    ADD COLUMN IF NOT EXISTS pending_public_key      TEXT,
    ADD COLUMN IF NOT EXISTS pending_key_fingerprint VARCHAR(64),
    ADD COLUMN IF NOT EXISTS pending_key_seen_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS auth_failures           INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_auth_failure_at    TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_transport          VARCHAR(10),
    ADD COLUMN IF NOT EXISTS last_ip                 VARCHAR(45),
    ADD COLUMN IF NOT EXISTS last_rssi               INT;

ALTER TABLE iot_devices DROP CONSTRAINT IF EXISTS chk_iot_device_transport;
ALTER TABLE iot_devices
    ADD CONSTRAINT chk_iot_device_transport CHECK (last_transport IS NULL OR last_transport IN ('USB', 'WIFI'));

-- One key belongs to one board: a key copied onto a second board (another deviceId) is refused.
CREATE UNIQUE INDEX IF NOT EXISTS uq_iot_devices_key_fingerprint
    ON iot_devices (key_fingerprint) WHERE key_fingerprint IS NOT NULL;
