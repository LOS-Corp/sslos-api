-- Smart machine search: machine load capacity and a per-branch turnover buffer.
-- Additive only; existing rows keep current behaviour (capacity unknown, no buffer).

ALTER TABLE machines
    ADD COLUMN capacity_kg NUMERIC(5, 1)
        CONSTRAINT chk_machines_capacity_kg CHECK (capacity_kg IS NULL OR capacity_kg > 0);

ALTER TABLE branch_booking_settings
    ADD COLUMN turnover_buffer_minutes INTEGER NOT NULL DEFAULT 0
        CONSTRAINT chk_booking_turnover_buffer CHECK (turnover_buffer_minutes BETWEEN 0 AND 120);
