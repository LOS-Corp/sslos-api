-- V16: Create machines table
-- Stores physical/simulated laundry machine state and IoT telemetry.
-- machine_code matches the MQTT topic segment (e.g., "WM001").

CREATE TABLE IF NOT EXISTS machines (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    machine_code         VARCHAR(50)  NOT NULL UNIQUE,
    branch_id            UUID,                            -- nullable during seeding
    type                 VARCHAR(20)  NOT NULL DEFAULT 'WASHER',
    status               VARCHAR(30)  NOT NULL DEFAULT 'OFFLINE',
    online               BOOLEAN      NOT NULL DEFAULT false,
    last_heartbeat_at    TIMESTAMPTZ,

    -- Telemetry fields (updated from MQTT telemetry topic)
    current_weight       NUMERIC(6,2) NOT NULL DEFAULT 0,
    water_level          NUMERIC(5,1) NOT NULL DEFAULT 0,
    vibration            NUMERIC(6,3) NOT NULL DEFAULT 0,
    remaining_seconds    INTEGER      NOT NULL DEFAULT 0,

    -- Status flags (updated from MQTT status topic)
    door_closed          BOOLEAN      NOT NULL DEFAULT true,
    leak_detected        BOOLEAN      NOT NULL DEFAULT false,
    error_code           VARCHAR(100) NOT NULL DEFAULT '',

    -- Metadata
    display_name         VARCHAR(100),
    location_description VARCHAR(200),

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_machine_type   CHECK (type   IN ('WASHER', 'DRYER')),
    CONSTRAINT chk_machine_status CHECK (status IN (
        'AVAILABLE', 'RESERVED', 'WAITING_START', 'RUNNING',
        'PAUSED', 'COMPLETED', 'OUT_OF_SERVICE', 'ERROR', 'OFFLINE'
    ))
);

CREATE INDEX IF NOT EXISTS idx_machine_code       ON machines(machine_code);
CREATE INDEX IF NOT EXISTS idx_machine_status     ON machines(status);
CREATE INDEX IF NOT EXISTS idx_machine_heartbeat  ON machines(last_heartbeat_at);
CREATE INDEX IF NOT EXISTS idx_machine_branch     ON machines(branch_id);

-- Seed the prototype washer WM001 so MQTT messages are immediately accepted.
-- branch_id is left NULL until branches are configured.
INSERT INTO machines (machine_code, type, status, display_name, location_description)
VALUES ('WM001', 'WASHER', 'OFFLINE', 'Washer WM001', 'Prototype / Wokwi Simulator')
ON CONFLICT (machine_code) DO NOTHING;
