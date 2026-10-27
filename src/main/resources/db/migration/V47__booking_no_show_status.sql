-- V47: NO_SHOW is a booking state of its own (SRS state machine, BR-07, UC-28): the check-in window closed
-- without a START. A no-show releases its resources like any other finished booking.

ALTER TABLE scheduled_bookings DROP CONSTRAINT IF EXISTS scheduled_bookings_status_check;
ALTER TABLE scheduled_bookings
    ADD CONSTRAINT scheduled_bookings_status_check CHECK (status IN (
        'HELD', 'CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING', 'READY_FOR_PICKUP',
        'COMPLETED', 'CANCELLED', 'EXPIRED', 'PAYMENT_REVIEW_REQUIRED', 'NO_SHOW'
    ));

CREATE OR REPLACE FUNCTION booking_holds_resources(status VARCHAR) RETURNS BOOLEAN
    LANGUAGE sql IMMUTABLE AS
$$ SELECT status NOT IN ('CANCELLED', 'EXPIRED', 'COMPLETED', 'PAYMENT_REVIEW_REQUIRED', 'NO_SHOW') $$;
