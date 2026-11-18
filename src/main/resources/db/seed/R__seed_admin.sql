-- Local/demo Admin account: admin@sslos.vn / Pass@123 — NEVER enable this location in production.
--
-- Not in db/migration on purpose: Flyway runs it only where db/seed is added to the locations, e.g.
--   SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/seed   (docker-compose.yml)
-- so a wiped local database gets its Admin back on the next start.
--
-- Repeatable (R__): Flyway runs it after the versioned migrations, and again only when this file changes.
-- Safe to re-run: an existing email or phone is left untouched (same id/phone as scripts/seed-demo-accounts.sql).

INSERT INTO users (id, full_name, email, phone, password_hash, status)
SELECT 'd0000000-0000-0000-0000-000000000001', 'Quản trị viên', 'admin@sslos.vn', '+84900000001',
       -- BCrypt (cost 10) of Pass@123
       '$2a$10$CurYcu8jPNEaHF0NkbM3OuSeUuTZ.d6Fs6kvzvNEVSONgO7Aw4cjO', 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'admin@sslos.vn' OR phone = '+84900000001');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id
FROM users u
JOIN roles r ON r.name = 'ADMIN'
WHERE u.email = 'admin@sslos.vn'
  AND NOT EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id);
