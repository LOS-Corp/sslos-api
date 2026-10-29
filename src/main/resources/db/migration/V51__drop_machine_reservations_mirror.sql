-- V51: booking_resource_allocations becomes the only record of who holds a machine (ADR-002 follow-up).
--
-- Until now every machine allocation was mirrored into machine_reservations, whose status also carried
-- the machine's cycle (CONFIRMED → ACTIVE → COMPLETED) and whose id the QR check-in stored. That state
-- moves onto the allocation itself, START authorizations point at the allocation and its booking, and
-- the mirror is dropped together with the tables of the removed counter/kiosk purchase flow.

-- 1. Per-machine cycle state on the allocation (washer and dryer allocations only).
ALTER TABLE booking_resource_allocations ADD COLUMN cycle_state VARCHAR(20);

UPDATE booking_resource_allocations SET cycle_state = 'PENDING' WHERE resource_type IN ('WASHER', 'DRYER');

UPDATE booking_resource_allocations a
SET cycle_state = CASE mr.status WHEN 'ACTIVE' THEN 'RUNNING' WHEN 'COMPLETED' THEN 'DONE' ELSE 'PENDING' END
FROM scheduled_bookings b, machine_reservations mr
WHERE a.booking_id = b.id
  AND mr.transaction_id = b.transaction_id
  AND mr.machine_id = a.resource_id
  AND a.resource_type IN ('WASHER', 'DRYER');

ALTER TABLE booking_resource_allocations ADD CONSTRAINT chk_allocation_cycle_state CHECK (
    (resource_type IN ('WASHER', 'DRYER') AND cycle_state IN ('PENDING', 'RUNNING', 'DONE'))
    OR (resource_type NOT IN ('WASHER', 'DRYER') AND cycle_state IS NULL));

CREATE INDEX idx_allocations_running ON booking_resource_allocations (resource_id) WHERE cycle_state = 'RUNNING';

-- 2. START authorizations refer to the allocation they start and to its booking.
ALTER TABLE machine_start_authorizations
    ADD COLUMN allocation_id UUID REFERENCES booking_resource_allocations (id) ON DELETE CASCADE,
    ADD COLUMN booking_id UUID REFERENCES scheduled_bookings (id) ON DELETE CASCADE;

UPDATE machine_start_authorizations msa
SET allocation_id = a.id, booking_id = b.id
FROM machine_reservations mr
JOIN scheduled_bookings b ON b.transaction_id = mr.transaction_id
JOIN booking_resource_allocations a ON a.booking_id = b.id AND a.resource_id = mr.machine_id
WHERE msa.reservation_id = mr.id;

-- Rows of the removed purchase flow have no booking to point at; they are history only.
DELETE FROM machine_start_authorizations WHERE allocation_id IS NULL AND NOT test_run;

ALTER TABLE machine_start_authorizations DROP CONSTRAINT chk_msa_reservation_or_test;
DROP INDEX IF EXISTS idx_msa_reservation;
ALTER TABLE machine_start_authorizations DROP COLUMN reservation_id;
ALTER TABLE machine_start_authorizations ADD CONSTRAINT chk_msa_allocation_or_test
    CHECK ((allocation_id IS NOT NULL AND booking_id IS NOT NULL) OR test_run);
CREATE INDEX idx_msa_allocation ON machine_start_authorizations (allocation_id);
CREATE INDEX idx_msa_booking ON machine_start_authorizations (booking_id);

-- 3. The mirror and the tables of the removed purchase / QR-token flows.
DROP TABLE IF EXISTS drop_off_locker_reservations;
DROP TABLE IF EXISTS laundry_service_orders;
DROP TABLE IF EXISTS machine_reservations;
DROP TABLE IF EXISTS qr_tokens;
