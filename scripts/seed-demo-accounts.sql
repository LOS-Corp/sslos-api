-- Demo accounts for local testing and the thesis demo — NEVER run against production.
-- Not a Flyway migration on purpose: it must not reach any real database.
--
-- Usage (local Docker DB):
--   docker exec -i postgres psql -U postgres -d postgres -v ON_ERROR_STOP=1 < scripts/seed-demo-accounts.sql
--
-- Every account: password Pass@123 (BCrypt, cost 10). Re-running the script is safe.
--
--   admin@sslos.vn       ADMIN     platform: Owner accounts, IoT devices
--   owner@sslos.vn       OWNER     every branch: prices, machines, staff, refunds, withdrawals
--   staff1@sslos.vn      STAFF     assigned to "Chi nhánh Trung tâm"
--   staff2@sslos.vn      STAFF     assigned to "Chi nhánh Quận 9" (shows branch scoping, BR-01)
--   customer1@sslos.vn   CUSTOMER  wallet 100.000 ₫ (to try wallet checkout and withdrawals)
--   customer2@sslos.vn   CUSTOMER  empty wallet

BEGIN;

-- Second branch so that Staff scoping can be seen; the Owner configures its hours and services in the UI.
INSERT INTO branches (id, name, address, status, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000002', 'Chi nhánh Quận 9', 'Quận 9, TP. Hồ Chí Minh', 'ACTIVE', NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

CREATE TEMP TABLE demo_accounts (id UUID, full_name TEXT, email TEXT, phone TEXT, role TEXT, branch_id UUID) ON COMMIT DROP;
INSERT INTO demo_accounts VALUES
    ('d0000000-0000-0000-0000-000000000001', 'Quản trị viên Demo', 'admin@sslos.vn',     '+84900000001', 'ADMIN',    NULL),
    ('d0000000-0000-0000-0000-000000000002', 'Chủ cơ sở Demo',     'owner@sslos.vn',     '+84900000002', 'OWNER',    NULL),
    ('d0000000-0000-0000-0000-000000000003', 'Nhân viên Trung tâm', 'staff1@sslos.vn',   '+84900000003', 'STAFF',    '00000000-0000-0000-0000-000000000001'),
    ('d0000000-0000-0000-0000-000000000004', 'Nhân viên Quận 9',   'staff2@sslos.vn',    '+84900000004', 'STAFF',    '00000000-0000-0000-0000-000000000002'),
    ('d0000000-0000-0000-0000-000000000005', 'Khách hàng Một',     'customer1@sslos.vn', '+84900000005', 'CUSTOMER', NULL),
    ('d0000000-0000-0000-0000-000000000006', 'Khách hàng Hai',     'customer2@sslos.vn', '+84900000006', 'CUSTOMER', NULL);

-- Pass@123; also resets the password and unlocks the account when it already exists.
INSERT INTO users (id, full_name, email, phone, password_hash, status)
SELECT id, full_name, email, phone, '$2a$10$CurYcu8jPNEaHF0NkbM3OuSeUuTZ.d6Fs6kvzvNEVSONgO7Aw4cjO', 'ACTIVE'
FROM demo_accounts
ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash, status = 'ACTIVE', updated_at = NOW();

-- Exactly one role per account (four-role model, task/09).
DELETE FROM user_roles ur USING users u, demo_accounts d WHERE ur.user_id = u.id AND u.email = d.email;
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM demo_accounts d JOIN users u ON u.email = d.email JOIN roles r ON r.name = d.role;

INSERT INTO branch_assignments (user_id, branch_id, assigned_by)
SELECT u.id, d.branch_id, (SELECT id FROM users WHERE email = 'owner@sslos.vn')
FROM demo_accounts d JOIN users u ON u.email = d.email
WHERE d.branch_id IS NOT NULL
ON CONFLICT (user_id, branch_id) DO NOTHING;

DELETE FROM login_attempts WHERE login_key IN (SELECT email FROM demo_accounts);

-- customer1: 100.000 ₫ through the ledger (append-only; the UNIQUE reference makes this run once).
INSERT INTO wallets (id, customer_id, balance_vnd)
SELECT gen_random_uuid(), u.id, 0 FROM users u WHERE u.email = 'customer1@sslos.vn'
ON CONFLICT (customer_id) DO NOTHING;
WITH credited AS (
    INSERT INTO wallet_entries (wallet_id, type, amount_vnd, reference, description)
    SELECT w.id, 'REFUND_CREDIT', 100000, 'demo-seed', 'Số dư demo để thử thanh toán bằng ví'
    FROM wallets w JOIN users u ON u.id = w.customer_id WHERE u.email = 'customer1@sslos.vn'
    ON CONFLICT (wallet_id, type, reference) DO NOTHING
    RETURNING wallet_id, amount_vnd)
UPDATE wallets w SET balance_vnd = w.balance_vnd + c.amount_vnd, updated_at = NOW()
FROM credited c WHERE w.id = c.wallet_id;

COMMIT;

SELECT u.email, r.name AS role, u.status, string_agg(b.name, ', ') AS branches, COALESCE(MAX(w.balance_vnd), 0) AS wallet_vnd
FROM users u
JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
LEFT JOIN branch_assignments ba ON ba.user_id = u.id LEFT JOIN branches b ON b.id = ba.branch_id
LEFT JOIN wallets w ON w.customer_id = u.id
WHERE u.email LIKE '%@sslos.vn'
GROUP BY u.email, r.name, u.status ORDER BY r.name, u.email;
