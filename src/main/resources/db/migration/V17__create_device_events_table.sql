-- V17: Create device_events table
-- Persists significant IoT events from machines.
-- High-frequency telemetry is NOT stored here (only current state in machines table).

CREATE TABLE IF NOT EXISTS device_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    machine_id   UUID         NOT NULL,
    machine_code VARCHAR(50)  NOT NULL,
    event_type   VARCHAR(50)  NOT NULL,
    detail       TEXT,
    raw_payload  VARCHAR(2000),
    booking_id   UUID,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_event_type CHECK (event_type IN (
        'STARTED', 'COMPLETED', 'DOOR_OPENED', 'DOOR_CLOSED',
        'LEAK_DETECTED', 'OVERLOAD', 'ESTOP', 'DEVICE_ERROR',
        'CONNECTION_RECOVERED', 'OFFLINE_DETECTED',
        'COMMAND_SENT', 'COMMAND_ACKNOWLEDGED', 'COMMAND_REJECTED',
        'COMMAND_TIMEOUT', 'STATUS_CHANGED'
    ))
);

-- Composite index for the most common query: events for a machine in time range
CREATE INDEX IF NOT EXISTS idx_device_event_machine_time ON device_events(machine_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_device_event_type         ON device_events(event_type);
CREATE INDEX IF NOT EXISTS idx_device_event_booking      ON device_events(booking_id) WHERE booking_id IS NOT NULL;
