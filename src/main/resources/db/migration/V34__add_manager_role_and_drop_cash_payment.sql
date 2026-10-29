-- V34: Add MANAGER role (branch manager) and make payments online-only.
-- Business decision 2026-10-12: no staff counter, no cash collection.

INSERT INTO roles (name) VALUES ('MANAGER')
ON CONFLICT (name) DO NOTHING;

-- Fails loudly if any legacy CASH payment exists; resolve those rows manually first.
ALTER TABLE payments DROP CONSTRAINT chk_payment_method;
ALTER TABLE payments ADD CONSTRAINT chk_payment_method CHECK (payment_method = 'ONLINE');
