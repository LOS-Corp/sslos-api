-- V60: The boards reach the backend only through the store's local MQTT broker; the USB serial link is gone.
-- Every board now signs each connection with its own key, so the transport of its last HELLO is no longer
-- worth storing.

ALTER TABLE iot_devices DROP CONSTRAINT IF EXISTS chk_iot_device_transport;
ALTER TABLE iot_devices DROP COLUMN IF EXISTS last_transport;
