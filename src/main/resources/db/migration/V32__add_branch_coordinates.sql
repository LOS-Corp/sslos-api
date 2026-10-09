-- V32: Add latitude/longitude coordinates to branches
-- Used to record the exact physical location of each branch and power
-- map-based discovery on the customer web.

ALTER TABLE branches
    ADD COLUMN IF NOT EXISTS latitude  DECIMAL(9, 6),
    ADD COLUMN IF NOT EXISTS longitude DECIMAL(9, 6);

-- Range validation: latitude -90..90, longitude -180..180
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_branch_latitude'
    ) THEN
        ALTER TABLE branches
            ADD CONSTRAINT chk_branch_latitude
            CHECK (latitude IS NULL OR (latitude >= -90  AND latitude <= 90));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_branch_longitude'
    ) THEN
        ALTER TABLE branches
            ADD CONSTRAINT chk_branch_longitude
            CHECK (longitude IS NULL OR (longitude >= -180 AND longitude <= 180));
    END IF;
END $$;
