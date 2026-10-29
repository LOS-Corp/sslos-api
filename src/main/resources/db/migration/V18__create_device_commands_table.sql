-- V18: Create device_commands table
-- Tracks commands issued from the backend to IoT devices.
-- Supports idempotency (command_id), ACK tracking, and timeout detection.

CREATE TABLE IF NOT EXISTS device_commands (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    command_id          VARCHAR(60)  NOT NULL UNIQUE,  -- echoed in ACK for correlation
    machine_id          UUID         NOT NULL,
    machine_code        VARCHAR(50)  NOT NULL,
    command             VARCHAR(30)  NOT NULL,          -- START, ESTOP, RESET, PAUSE, etc.
    booking_id          UUID,
    issued_by_user_id   UUID,
    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    sent_at             TIMESTAMPTZ,
    acknowledged_at     TIMESTAMPTZ,
    ack_result          VARCHAR(30),
    ack_reason          VARCHAR(500),
    timeout_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_cmd_status CHECK (status IN (
        'PENDING', 'SENT', 'ACKNOWLEDGED', 'REJECTED', 'TIMEOUT', 'FAILED'
    ))
);

-- Composite index for the primary query: commands for a machine sorted by time
CREATE INDEX IF NOT EXISTS idx_device_cmd_machine_time ON device_commands(machine_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_device_cmd_status       ON device_commands(status);
CREATE INDEX IF NOT EXISTS idx_device_cmd_booking      ON device_commands(booking_id) WHERE booking_id IS NOT NULL;
-- Index for timeout scheduler query: find SENT commands past their deadline
CREATE INDEX IF NOT EXISTS idx_device_cmd_timeout      ON device_commands(status, timeout_at) WHERE status = 'SENT';
