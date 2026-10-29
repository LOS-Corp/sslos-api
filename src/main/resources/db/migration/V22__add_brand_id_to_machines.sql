-- V22: Add brand_id to machines for multi-brand architecture
-- Supports multiple brands having the same machine code (e.g., BR001/WM001 and BR002/WM001).

ALTER TABLE machines
    ADD COLUMN IF NOT EXISTS brand_id VARCHAR(50) NOT NULL DEFAULT 'BR001';

-- Update any existing records to default brand 'BR001'
UPDATE machines
SET brand_id = 'BR001'
WHERE brand_id IS NULL OR brand_id = '';

-- Drop global uniqueness on machine_code if exists
ALTER TABLE machines
    DROP CONSTRAINT IF EXISTS machines_machine_code_key;

DROP INDEX IF EXISTS idx_machine_code;

-- Enforce multi-brand composite unique constraint: (brand_id, machine_code)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_machines_brand_machine'
    ) THEN
        ALTER TABLE machines
            ADD CONSTRAINT uq_machines_brand_machine
            UNIQUE (brand_id, machine_code);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_machine_brand_code
    ON machines(brand_id, machine_code);

CREATE INDEX IF NOT EXISTS idx_machine_code
    ON machines(machine_code);
