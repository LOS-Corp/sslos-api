-- V44: The database itself refuses two live bookings on one resource at the same time (BR-02, NFR-06, SC-01).
-- The application still checks first (to answer MSG07 with suggestions); this constraint is the last line
-- of defence if that check is ever bypassed or races.

CREATE EXTENSION IF NOT EXISTS btree_gist;

ALTER TABLE booking_resource_allocations ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- An allocation is live while its booking still holds the resource.
CREATE FUNCTION booking_holds_resources(status VARCHAR) RETURNS BOOLEAN
    LANGUAGE sql IMMUTABLE AS
$$ SELECT status NOT IN ('CANCELLED', 'EXPIRED', 'COMPLETED', 'PAYMENT_REVIEW_REQUIRED') $$;

UPDATE booking_resource_allocations a
SET active = booking_holds_resources(b.status)
FROM scheduled_bookings b
WHERE b.id = a.booking_id;

-- Every status change of a booking (job, cancel, payment, locker flow…) keeps its allocations in step.
CREATE FUNCTION sync_allocation_active() RETURNS trigger AS $$
BEGIN
    UPDATE booking_resource_allocations
    SET active = booking_holds_resources(NEW.status)
    WHERE booking_id = NEW.id
      AND active IS DISTINCT FROM booking_holds_resources(NEW.status);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_booking_status_sync_allocations
    AFTER UPDATE OF status ON scheduled_bookings
    FOR EACH ROW
    WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION sync_allocation_active();

-- Half-open intervals: one booking may end exactly when the next one starts.
ALTER TABLE booking_resource_allocations
    ADD CONSTRAINT ex_allocation_no_overlap
    EXCLUDE USING gist (resource_id WITH =, tstzrange(occupied_from, occupied_until, '[)') WITH &&)
    WHERE (active);
