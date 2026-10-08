-- V28: Sync device_events CHECK constraint with the new DeviceEventType enum.
--
-- V24 added MACHINE_REGISTERED but left the historical ESTOP value in the
-- chk_event_type constraint. The ESP32 firmware v2 no longer sends ESTOP
-- (the physical STOP button was removed), so we replaced ESTOP with:
--   * STOP_COMMAND — audit row for remote STOP commands.
--   * EXCESSIVE_VIBRATION — safety error.
--
-- This migration rewrites the CHECK constraint to match the Java enum
-- without touching existing rows.

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
        'COMMAND_TIMEOUT', 'STATUS_CHANGED', 'MACHINE_REGISTERED'
    ));