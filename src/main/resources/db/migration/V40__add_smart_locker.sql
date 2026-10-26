-- V40: Smart locker (assisted laundry).
--
-- A locker cabinet is a machine of type LOCKER, so it reuses device pairing,
-- per-device MQTT identity, online tracking and commissioning. Each compartment
-- stays a LOCKER row in branch_service_resources (what bookings allocate) and
-- points at its cabinet slot.
--
-- Flow: CONFIRMED --customer drop-off PIN, door closed--> CHECKED_IN
--       --staff collects--> IN_PROGRESS --staff returns--> READY_FOR_PICKUP
--       --customer pickup PIN, door closed--> COMPLETED

ALTER TABLE machines DROP CONSTRAINT IF EXISTS chk_machine_type;
ALTER TABLE machines
    ADD CONSTRAINT chk_machine_type CHECK (type IN ('WASHER', 'DRYER', 'LOCKER'));

ALTER TABLE branch_service_resources
    ADD COLUMN IF NOT EXISTS locker_machine_id UUID REFERENCES machines (id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS compartment_no SMALLINT CHECK (compartment_no BETWEEN 1 AND 8);

CREATE UNIQUE INDEX IF NOT EXISTS uq_locker_compartment
    ON branch_service_resources (locker_machine_id, compartment_no)
    WHERE locker_machine_id IS NOT NULL;

ALTER TABLE scheduled_bookings DROP CONSTRAINT IF EXISTS scheduled_bookings_status_check;
ALTER TABLE scheduled_bookings
    ADD CONSTRAINT scheduled_bookings_status_check CHECK (status IN (
        'HELD', 'CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING', 'READY_FOR_PICKUP',
        'COMPLETED', 'CANCELLED', 'EXPIRED', 'PAYMENT_REVIEW_REQUIRED'
    ));

-- One row per compartment opening: a customer PIN (DROP_OFF / PICKUP) or a
-- staff action (STAFF_COLLECT / STAFF_RETURN / STAFF_INSPECT, no PIN).
-- ACTIVE = PIN waiting to be typed; OPENING = unlocked, waiting for the door
-- to open and close; USED = done; REVOKED = replaced or booking ended.
CREATE TABLE IF NOT EXISTS locker_accesses (
    id               UUID         PRIMARY KEY,
    booking_id       UUID         REFERENCES scheduled_bookings (id) ON DELETE CASCADE,
    resource_id      UUID         NOT NULL REFERENCES branch_service_resources (id) ON DELETE CASCADE,
    machine_id       UUID         NOT NULL REFERENCES machines (id) ON DELETE CASCADE,
    purpose          VARCHAR(20)  NOT NULL CHECK (purpose IN (
                         'DROP_OFF', 'PICKUP', 'STAFF_COLLECT', 'STAFF_RETURN', 'STAFF_INSPECT')),
    pin              VARCHAR(10),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE', 'OPENING', 'USED', 'REVOKED')),
    valid_from       TIMESTAMPTZ  NOT NULL,
    valid_until      TIMESTAMPTZ  NOT NULL,
    open_command_id  VARCHAR(80),
    opened_by        UUID,
    opened_at        TIMESTAMPTZ,
    used_at          TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- A PIN only has to be unique among the live codes of one cabinet.
CREATE UNIQUE INDEX IF NOT EXISTS uq_locker_live_pin
    ON locker_accesses (machine_id, pin)
    WHERE pin IS NOT NULL AND status IN ('ACTIVE', 'OPENING');

CREATE INDEX IF NOT EXISTS idx_locker_accesses_booking ON locker_accesses (booking_id);
