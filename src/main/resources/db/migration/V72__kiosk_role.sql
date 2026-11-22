-- V72: in-store kiosk. A KIOSK account (created by the Owner, signed in once on the kiosk screen) books and pays
-- for walk-in customers without an account ("dùng ngay"). Its bookings belong to the kiosk account; money owed back
-- to such a customer never goes to the kiosk's wallet but to a refund request Staff/Owner settle in person.
INSERT INTO roles (name) VALUES ('KIOSK')
ON CONFLICT (name) DO NOTHING;
