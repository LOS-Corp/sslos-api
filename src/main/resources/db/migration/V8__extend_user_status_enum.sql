-- Add LOCKED and SUSPENDED status options to users table
-- This extends the existing status CHECK constraint

-- Drop existing constraint
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_status_check;

-- Add new constraint with all 4 statuses
ALTER TABLE users ADD CONSTRAINT users_status_check 
    CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED', 'SUSPENDED'));
