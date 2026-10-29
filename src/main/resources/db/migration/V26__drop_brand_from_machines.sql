-- V26: Drop brand_id from machines, enforce branch_id NOT NULL, replace composite unique
-- MQTT topics are now branch-scoped: laundry/washer/{branchId-UUID}/{machineId}/{channel}
-- Multi-brand identity is no longer used; the (branch_id, machine_code) composite key
-- replaces the (brand_id, machine_code) composite key.

-- 1. Drop the old brand-scoped unique constraint / index
ALTER TABLE machines
    DROP CONSTRAINT IF EXISTS uq_machines_brand_machine;

DROP INDEX IF EXISTS idx_machine_brand_code;

-- 2. Drop brand_id column (idempotent)
ALTER TABLE machines
    DROP COLUMN IF EXISTS brand_id;

-- 3. Backfill branch_id for any machines missing a branch (assign the prototype branch)
UPDATE machines
SET branch_id = '00000000-0000-0000-0000-000000000001'
WHERE branch_id IS NULL;

-- 4. Enforce NOT NULL on branch_id
ALTER TABLE machines
    ALTER COLUMN branch_id SET NOT NULL;

-- 5. Recreate the branch-scoped composite unique constraint
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_machines_branch_machine'
    ) THEN
        ALTER TABLE machines
            ADD CONSTRAINT uq_machines_branch_machine
            UNIQUE (branch_id, machine_code);
    END IF;
END $$;

-- 6. Composite index for fast (branch_id, machine_code) lookups
CREATE INDEX IF NOT EXISTS idx_machine_branch_code
    ON machines(branch_id, machine_code);