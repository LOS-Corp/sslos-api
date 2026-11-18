-- V61: washing is priced by weight instead of by program.
--
-- * A washer service costs   price (fixed part, e.g. the dryer of WASH_DRY) + price_per_kg x booked kg.
--   The customer books whole kilograms; scheduled_bookings.declared_load_kg holds the kilograms paid for
--   (raised when the customer pays for extra kilograms at the machine).
-- * Programs no longer change the price: the customer does not pick one, the machine runs the default.
-- * A scale (HX711) next to each washer weighs the laundry before START. Up to 0.5 kg over the paid
--   kilograms is free; above that the customer pays the extra whole kilograms (booking_load_topups) or
--   takes laundry out; above the machine's capacity they move to a larger free washer or take laundry out.

ALTER TABLE service_offerings DROP CONSTRAINT IF EXISTS service_offerings_price_check;
ALTER TABLE service_offerings ADD CONSTRAINT service_offerings_price_check CHECK (price >= 0);
ALTER TABLE service_offerings ADD COLUMN price_per_kg NUMERIC(10, 2);
ALTER TABLE service_offerings ADD CONSTRAINT service_offerings_price_per_kg_check
    CHECK (price_per_kg IS NULL OR price_per_kg > 0);

-- Washer services: 10 000 d/kg. WASH_ONLY keeps no fixed part; WASH_DRY keeps the dryer part (as DRY_ONLY).
UPDATE service_offerings o SET price_per_kg = 10000
WHERE EXISTS (SELECT 1 FROM booking_service_requirements r
              WHERE r.service_code = o.service_code AND r.resource_type = 'WASHER');
UPDATE service_offerings SET price = 0 WHERE service_code = 'WASH_ONLY';
UPDATE service_offerings SET price = COALESCE((SELECT price FROM service_offerings WHERE service_code = 'DRY_ONLY'), 20000)
WHERE service_code = 'WASH_DRY';

-- Programs are free: the surcharge column stays for history but is no longer charged.
UPDATE machine_programs SET surcharge = 0;

-- Result of weighing on the START authorization (one scale reading at a time per authorization).
ALTER TABLE machine_start_authorizations
    ADD COLUMN measured_load_kg NUMERIC(5, 2),
    ADD COLUMN load_verdict VARCHAR(20),
    ADD COLUMN load_extra_kg INTEGER,
    ADD COLUMN load_checked_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_msa_load_verdict
        CHECK (load_verdict IS NULL OR load_verdict IN ('ACCEPTED', 'OVER_BOOKED', 'OVER_CAPACITY'));

-- Extra kilograms paid at the machine. The transaction is its own payment (wallet / payOS) linked to the booking.
CREATE TABLE booking_load_topups (
    transaction_id   UUID PRIMARY KEY REFERENCES transactions(id) ON DELETE CASCADE,
    booking_id       UUID NOT NULL REFERENCES scheduled_bookings(id) ON DELETE CASCADE,
    authorization_id UUID REFERENCES machine_start_authorizations(id) ON DELETE SET NULL,
    extra_kg         INTEGER NOT NULL CHECK (extra_kg > 0),
    price_per_kg     NUMERIC(10, 2) NOT NULL CHECK (price_per_kg > 0),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'PAID', 'CANCELLED')),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    paid_at          TIMESTAMPTZ
);
CREATE INDEX idx_booking_load_topups_booking ON booking_load_topups (booking_id);
-- At most one unpaid top-up per authorization.
CREATE UNIQUE INDEX uq_booking_load_topups_pending
    ON booking_load_topups (authorization_id) WHERE status = 'PENDING';
