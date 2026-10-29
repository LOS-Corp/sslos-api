-- V21: Create branches table and link machines
-- Supports branch/store multi-tenancy across machines, transactions, and refunds.

CREATE TABLE IF NOT EXISTS branches (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120) NOT NULL UNIQUE,
    address     VARCHAR(255),
    phone       VARCHAR(20),
    status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_branch_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX IF NOT EXISTS idx_branch_name   ON branches(name);
CREATE INDEX IF NOT EXISTS idx_branch_status ON branches(status);

-- Seed a default prototype branch if not present
INSERT INTO branches (id, name, address, phone, status)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'Chi nhánh Trung tâm',
    '123 Đường Số 1, Quận 1, TP.HCM',
    '0901234567',
    'ACTIVE'
)
ON CONFLICT (id) DO NOTHING;

-- Associate prototype washer WM001 with default branch if branch_id is currently null
UPDATE machines
SET branch_id = '00000000-0000-0000-0000-000000000001'
WHERE machine_code = 'WM001' AND branch_id IS NULL;

-- Clean any orphaned branch_id values on machines before adding FK
UPDATE machines
SET branch_id = NULL
WHERE branch_id IS NOT NULL
  AND branch_id NOT IN (SELECT id FROM branches);

-- Add foreign key constraint machines -> branches (SET NULL on delete)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_machines_branch'
    ) THEN
        ALTER TABLE machines
            ADD CONSTRAINT fk_machines_branch
            FOREIGN KEY (branch_id)
            REFERENCES branches(id)
            ON DELETE SET NULL;
    END IF;
END $$;
