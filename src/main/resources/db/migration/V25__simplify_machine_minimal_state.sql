-- V25: Simplify Machine - remove high-frequency telemetry columns from machines table
-- PostgreSQL is NOT a telemetry sink. Telemetry is streamed in realtime via MQTT -> WebSocket.

ALTER TABLE machines
    DROP COLUMN IF EXISTS current_weight,
    DROP COLUMN IF EXISTS water_level,
    DROP COLUMN IF EXISTS vibration,
    DROP COLUMN IF EXISTS remaining_seconds,
    DROP COLUMN IF EXISTS door_closed,
    DROP COLUMN IF EXISTS leak_detected;
