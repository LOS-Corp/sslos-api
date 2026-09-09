ALTER TABLE users RENAME COLUMN customer_id TO public_user_id;

INSERT INTO roles (name)
VALUES ('ADMIN'), ('OWNER'), ('STAFF'), ('CUSTOMER')
ON CONFLICT (name) DO NOTHING;