-- Add ADMIN role if not exists
INSERT INTO roles (name) VALUES ('ADMIN')
ON CONFLICT (name) DO NOTHING;

-- Update user_roles for khanhkhoi08@gmail.com to ADMIN
UPDATE user_roles ur
SET role_id = r.id
FROM users u, roles r
WHERE u.email = 'khanhkhoi08@gmail.com'
  AND ur.user_id = u.id
  AND r.name = 'ADMIN';
