-- V58: single store. The system runs on a local server inside one store (reached from outside through
-- Cloudflare Tunnel), so the branch concept is gone:
--   branches + branch_booking_settings  -> store_settings (exactly one row)
--   branch_booking_hours                -> store_hours        (keyed by day_of_week)
--   branch_service_offerings            -> service_offerings  (keyed by service_code)
--   branch_service_resources            -> service_resources  (resource_code unique)
--   branch_assignments                  -> dropped (every Staff works in the store)
--   branch_id on machines, scheduled_bookings, refund_requests, audit_logs -> dropped
--
-- Data: the store is the seeded 00000000-…-0001 row if present, otherwise the oldest ACTIVE branch,
-- otherwise the oldest branch. Configuration rows (hours, offerings) of other branches are dropped;
-- operational rows (machines, resources, bookings, refunds, audit) all move into the store, and a
-- machine or resource code that would clash gets a "-<first 4 hex of its old branch id>" suffix.

-- ── 1. Which branch becomes the store ───────────────────────────────────────────────────────────
INSERT INTO branches (id, name, status)
SELECT '00000000-0000-0000-0000-000000000001', 'Cửa hàng LaundryOS', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM branches);

CREATE TEMP TABLE v58_store ON COMMIT DROP AS
SELECT id FROM branches
ORDER BY (id = '00000000-0000-0000-0000-000000000001') DESC, (status = 'ACTIVE') DESC, created_at, id
LIMIT 1;

-- ── 2. store_settings ───────────────────────────────────────────────────────────────────────────
CREATE TABLE store_settings (
    id                      SMALLINT     PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    name                    VARCHAR(120) NOT NULL,
    address                 VARCHAR(255),
    phone                   VARCHAR(20),
    map_embed_url           VARCHAR(500),
    -- NULL until the Owner sets the booking timezone (the store is not bookable before that).
    timezone                VARCHAR(80),
    turnover_buffer_minutes INTEGER      NOT NULL DEFAULT 0
        CONSTRAINT chk_store_turnover_buffer CHECK (turnover_buffer_minutes BETWEEN 0 AND 120),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

INSERT INTO store_settings (id, name, address, phone, map_embed_url, timezone, turnover_buffer_minutes,
                            created_at, updated_at)
SELECT 1, CASE WHEN b.name = 'Chi nhánh Trung tâm' THEN 'Cửa hàng LaundryOS' ELSE b.name END, b.address, b.phone, b.map_embed_url, s.timezone, COALESCE(s.turnover_buffer_minutes, 0),
       b.created_at, b.updated_at
FROM branches b
LEFT JOIN branch_booking_settings s ON s.branch_id = b.id
WHERE b.id = (SELECT id FROM v58_store);

-- ── 3. store_hours ──────────────────────────────────────────────────────────────────────────────
DELETE FROM branch_booking_hours WHERE branch_id <> (SELECT id FROM v58_store);
ALTER TABLE branch_booking_hours DROP CONSTRAINT branch_booking_hours_branch_id_fkey;
ALTER TABLE branch_booking_hours DROP CONSTRAINT branch_booking_hours_pkey;
ALTER TABLE branch_booking_hours DROP COLUMN branch_id;
ALTER TABLE branch_booking_hours RENAME TO store_hours;
ALTER TABLE store_hours ADD CONSTRAINT store_hours_pkey PRIMARY KEY (day_of_week);
ALTER TABLE store_hours RENAME CONSTRAINT branch_booking_hours_check TO store_hours_check;
ALTER TABLE store_hours RENAME CONSTRAINT branch_booking_hours_day_of_week_check TO store_hours_day_of_week_check;

DROP TABLE branch_booking_settings;

-- ── 4. service_offerings ────────────────────────────────────────────────────────────────────────
DELETE FROM branch_service_offerings WHERE branch_id <> (SELECT id FROM v58_store);
ALTER TABLE branch_service_offerings DROP CONSTRAINT branch_service_offerings_branch_id_fkey;
ALTER TABLE branch_service_offerings DROP CONSTRAINT branch_service_offerings_pkey;
ALTER TABLE branch_service_offerings DROP COLUMN branch_id;
ALTER TABLE branch_service_offerings RENAME TO service_offerings;
ALTER TABLE service_offerings ADD CONSTRAINT service_offerings_pkey PRIMARY KEY (service_code);
ALTER TABLE service_offerings RENAME CONSTRAINT branch_service_offerings_price_check TO service_offerings_price_check;
ALTER TABLE service_offerings RENAME CONSTRAINT branch_service_offerings_processing_minutes_override_check
    TO service_offerings_processing_minutes_check;
ALTER TABLE service_offerings RENAME CONSTRAINT branch_service_offerings_secondary_processing_minutes_ove_check
    TO service_offerings_secondary_processing_minutes_check;
ALTER TABLE service_offerings RENAME CONSTRAINT branch_service_offerings_service_code_fkey
    TO service_offerings_service_code_fkey;

-- ── 5. service_resources (kept for every branch: bookings and locker accesses point at them) ────
UPDATE branch_service_resources r
SET resource_code = LEFT(r.resource_code, 59) || '-' || LEFT(r.branch_id::text, 4)
WHERE r.branch_id <> (SELECT id FROM v58_store)
  AND EXISTS (SELECT 1 FROM branch_service_resources o
              WHERE o.resource_code = r.resource_code AND o.id <> r.id
                AND (o.branch_id = (SELECT id FROM v58_store) OR o.branch_id < r.branch_id));
ALTER TABLE branch_service_resources DROP CONSTRAINT branch_service_resources_branch_id_fkey;
ALTER TABLE branch_service_resources DROP CONSTRAINT branch_service_resources_branch_id_resource_code_key;
ALTER TABLE branch_service_resources DROP COLUMN branch_id;
ALTER TABLE branch_service_resources RENAME TO service_resources;
ALTER TABLE service_resources RENAME CONSTRAINT branch_service_resources_pkey TO service_resources_pkey;
ALTER TABLE service_resources ADD CONSTRAINT service_resources_resource_code_key UNIQUE (resource_code);
ALTER TABLE service_resources RENAME CONSTRAINT branch_service_resources_compartment_no_check
    TO service_resources_compartment_no_check;
ALTER TABLE service_resources RENAME CONSTRAINT branch_service_resources_locker_machine_id_fkey
    TO service_resources_locker_machine_id_fkey;
ALTER TABLE service_resources RENAME CONSTRAINT branch_service_resources_resource_type_check
    TO service_resources_resource_type_check;

-- ── 6. machines: machine_code becomes unique on its own ─────────────────────────────────────────
UPDATE machines m
SET machine_code = LEFT(m.machine_code, 45) || '-' || UPPER(LEFT(m.branch_id::text, 4))
WHERE m.branch_id <> (SELECT id FROM v58_store)
  AND EXISTS (SELECT 1 FROM machines o
              WHERE o.machine_code = m.machine_code AND o.id <> m.id
                AND (o.branch_id = (SELECT id FROM v58_store) OR o.branch_id < m.branch_id));
ALTER TABLE machines DROP CONSTRAINT fk_machines_branch;
ALTER TABLE machines DROP CONSTRAINT uq_machines_branch_machine;
DROP INDEX IF EXISTS idx_machine_branch;
DROP INDEX IF EXISTS idx_machine_branch_code;
ALTER TABLE machines DROP COLUMN branch_id;
ALTER TABLE machines ADD CONSTRAINT uq_machines_machine_code UNIQUE (machine_code);

-- ── 7. bookings, refunds, audit ─────────────────────────────────────────────────────────────────
DROP INDEX IF EXISTS idx_scheduled_bookings_branch_start;
DROP INDEX IF EXISTS idx_scheduled_bookings_advance_live;
ALTER TABLE scheduled_bookings DROP CONSTRAINT scheduled_bookings_branch_id_fkey;
ALTER TABLE scheduled_bookings DROP COLUMN branch_id;
CREATE INDEX idx_scheduled_bookings_start ON scheduled_bookings (start_time);
CREATE INDEX idx_scheduled_bookings_advance_live ON scheduled_bookings (start_time) WHERE advance_booking;

DROP INDEX IF EXISTS idx_refund_requests_branch_id;
DROP INDEX IF EXISTS idx_refund_requests_pending;
ALTER TABLE refund_requests DROP CONSTRAINT IF EXISTS fk_refund_branch;
ALTER TABLE refund_requests DROP COLUMN branch_id;
CREATE INDEX idx_refund_requests_pending ON refund_requests (status)
    WHERE status IN ('CREATED', 'PENDING_APPROVAL');

DROP INDEX IF EXISTS idx_audit_logs_branch;
ALTER TABLE audit_logs DROP COLUMN branch_id;

-- ── 8. gone ─────────────────────────────────────────────────────────────────────────────────────
DROP TABLE branch_assignments;
DROP TABLE branches;
