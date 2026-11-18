-- Demo accounts for local testing and the thesis demo — NEVER run against production.
-- Not a Flyway migration on purpose: it must not reach any real database.
-- (Only the Admin has a Flyway seed, db/seed/R__seed_admin.sql, enabled by docker-compose.yml.)
--
-- Usage (local Docker DB of docker-compose.yml):
--   docker exec -i sslos-postgres-1 psql -U sslos -d sslos -v ON_ERROR_STOP=1 < scripts/seed-demo-accounts.sql
--
-- Single store (V58): there are no branches; every Staff works in the one store (store_settings, id = 1).
-- Every account: password Pass@123 (BCrypt, cost 10). Re-running the script is safe.
--
--   admin@sslos.vn       ADMIN     platform: Owner accounts, IoT devices
--   owner@sslos.vn       OWNER     the store: prices, machines, staff, refunds, withdrawals
--   staff1@sslos.vn      STAFF     the store
--   staff2@sslos.vn      STAFF     the store
--   customer1@sslos.vn   CUSTOMER  wallet 100.000 ₫ (to try wallet checkout and withdrawals)
--   customer2@sslos.vn   CUSTOMER  empty wallet
--
-- Store booking settings, only where the Owner has not set them yet (an existing configuration is kept):
--   timezone Asia/Ho_Chi_Minh, open every day 07:00–22:00.
-- Prices, services and machines are still configured by the Owner on the management web.

BEGIN;

CREATE TEMP TABLE demo_accounts (id UUID, full_name TEXT, email TEXT, phone TEXT, role TEXT) ON COMMIT DROP;
INSERT INTO demo_accounts VALUES
    ('d0000000-0000-0000-0000-000000000001', 'Quản trị viên Demo', 'admin@sslos.vn',     '+84900000001', 'ADMIN'),
    ('d0000000-0000-0000-0000-000000000002', 'Chủ cửa hàng Demo',  'owner@sslos.vn',     '+84900000002', 'OWNER'),
    ('d0000000-0000-0000-0000-000000000003', 'Nhân viên Một',      'staff1@sslos.vn',    '+84900000003', 'STAFF'),
    ('d0000000-0000-0000-0000-000000000004', 'Nhân viên Hai',      'staff2@sslos.vn',    '+84900000004', 'STAFF'),
    ('d0000000-0000-0000-0000-000000000005', 'Khách hàng Một',     'customer1@sslos.vn', '+84900000005', 'CUSTOMER'),
    ('d0000000-0000-0000-0000-000000000006', 'Khách hàng Hai',     'customer2@sslos.vn', '+84900000006', 'CUSTOMER');

-- Pass@123; also resets the password and unlocks the account when it already exists.
INSERT INTO users (id, full_name, email, phone, password_hash, status)
SELECT id, full_name, email, phone, '$2a$10$CurYcu8jPNEaHF0NkbM3OuSeUuTZ.d6Fs6kvzvNEVSONgO7Aw4cjO', 'ACTIVE'
FROM demo_accounts
ON CONFLICT (email) DO UPDATE SET password_hash = EXCLUDED.password_hash, status = 'ACTIVE', updated_at = NOW();

-- Exactly one role per account (four-role model, task/09).
DELETE FROM user_roles ur USING users u, demo_accounts d WHERE ur.user_id = u.id AND u.email = d.email;
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM demo_accounts d JOIN users u ON u.email = d.email JOIN roles r ON r.name = d.role;

DELETE FROM login_attempts WHERE login_key IN (SELECT email FROM demo_accounts);

-- The store row exists after V58; create it only on a database where it is somehow missing.
INSERT INTO store_settings (id, name) VALUES (1, 'Cửa hàng LaundryOS') ON CONFLICT (id) DO NOTHING;
UPDATE store_settings SET timezone = 'Asia/Ho_Chi_Minh', updated_at = NOW() WHERE id = 1 AND timezone IS NULL;

-- Opening hours (ISO day 1 = Monday … 7 = Sunday), only when none are set.
INSERT INTO store_hours (day_of_week, opens_at, closes_at)
SELECT d, TIME '07:00', TIME '22:00' FROM generate_series(1, 7) AS d
WHERE NOT EXISTS (SELECT 1 FROM store_hours);

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

SELECT u.email, r.name AS role, u.status, COALESCE(MAX(w.balance_vnd), 0) AS wallet_vnd
FROM users u
JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id
LEFT JOIN wallets w ON w.customer_id = u.id
WHERE u.email LIKE '%@sslos.vn'
GROUP BY u.email, r.name, u.status ORDER BY r.name, u.email;

SELECT s.name, s.timezone, COUNT(h.day_of_week) AS open_days
FROM store_settings s LEFT JOIN store_hours h ON TRUE
GROUP BY s.name, s.timezone;
