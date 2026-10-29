-- V36: Admin test-run START authorizations.
--
-- Lets an ADMIN/OWNER unlock the physical START button from the admin console
-- without a customer booking or QR check-in, to test a machine end to end.
-- Such rows have no reservation and are flagged test_run = TRUE; everything
-- after AUTHORIZE_START (START_REQUEST → START_APPROVAL → STARTED) is unchanged.

ALTER TABLE machine_start_authorizations
    ALTER COLUMN reservation_id DROP NOT NULL;

ALTER TABLE machine_start_authorizations
    ADD COLUMN IF NOT EXISTS test_run BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE machine_start_authorizations
    ADD CONSTRAINT chk_msa_reservation_or_test
        CHECK (reservation_id IS NOT NULL OR test_run);
