-- V46: Facts the booking rules need, stored at hold time instead of being guessed later.
-- advance_booking: the start was more than the use-now window (15 min) after the hold (BR-27 quota counts these).
-- declared_load_kg: what the customer said they bring; never booked on a smaller machine (BR-26).

ALTER TABLE scheduled_bookings
    ADD COLUMN advance_booking  BOOLEAN       NOT NULL DEFAULT FALSE,
    ADD COLUMN declared_load_kg NUMERIC(5, 1) CHECK (declared_load_kg IS NULL OR declared_load_kg > 0);

UPDATE scheduled_bookings SET advance_booking = (start_time > created_at + interval '15 minutes');

CREATE INDEX idx_scheduled_bookings_advance_live
    ON scheduled_bookings (branch_id, start_time)
    WHERE advance_booking;
