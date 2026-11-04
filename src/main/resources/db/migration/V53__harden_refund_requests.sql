-- V53: Harden refund_requests (follow-up to V15).
--  1. DB-level refund cap per payment (a trigger, so no write path can bypass RefundService).
--  2. status must be a known RefundStatus value.
--  3. FKs for transaction_id, branch_id, requested_by, approved_by.

-- ── 0. Refuse to continue if existing data is already over-refunded ──────────
-- The trigger below only checks new writes, so legacy violations must be fixed first.
DO $$
DECLARE
    bad_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO bad_count FROM (
        SELECT p.id
        FROM payments p
        JOIN refund_requests r ON r.payment_id = p.id
        WHERE r.status NOT IN ('REJECTED', 'FAILED')
        GROUP BY p.id, p.amount
        HAVING SUM(r.amount) > p.amount
    ) over_refunded;

    IF bad_count > 0 THEN
        RAISE EXCEPTION 'V53: % payment(s) already have refunds above the amount paid; fix the data first', bad_count;
    END IF;
END $$;

-- ── 1. Refund cap enforced in the database ────────────────────────────────────
-- Mirrors RefundService.countedRefunds(): REJECTED and FAILED do not count.
-- Locking the payment row serialises concurrent writers for the same payment,
-- and the SUM runs after that lock, so it sees the other transaction's committed rows.
CREATE OR REPLACE FUNCTION fn_refund_within_payment_cap() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    paid_amount     NUMERIC(10, 2);
    refunded_amount NUMERIC(10, 2);
BEGIN
    IF NEW.status IN ('REJECTED', 'FAILED') THEN
        RETURN NEW;
    END IF;

    SELECT amount INTO paid_amount FROM payments WHERE id = NEW.payment_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'payment % does not exist', NEW.payment_id USING ERRCODE = 'foreign_key_violation';
    END IF;

    SELECT COALESCE(SUM(amount), 0) INTO refunded_amount
    FROM refund_requests
    WHERE payment_id = NEW.payment_id
      AND status NOT IN ('REJECTED', 'FAILED')
      AND id <> NEW.id;

    IF refunded_amount + NEW.amount > paid_amount THEN
        RAISE EXCEPTION 'refunds for payment % would total % which exceeds the % paid',
            NEW.payment_id, refunded_amount + NEW.amount, paid_amount
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_refund_within_payment_cap ON refund_requests;
CREATE TRIGGER trg_refund_within_payment_cap
    BEFORE INSERT OR UPDATE OF amount, status, payment_id ON refund_requests
    FOR EACH ROW EXECUTE FUNCTION fn_refund_within_payment_cap();

-- ── 2. Known statuses only (CREATED kept for rows written before V41) ─────────
ALTER TABLE refund_requests
    ADD CONSTRAINT chk_refund_status CHECK (status IN (
        'CREATED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED',
        'PROCESSING', 'COMPLETED', 'FAILED'));

-- ── 3. Referential integrity for the columns V15 left open ────────────────────
-- NOT VALID: enforced for new and updated rows at once, without scanning legacy rows.
-- Run ALTER TABLE ... VALIDATE CONSTRAINT later, once orphan rows (if any) are cleaned up.
ALTER TABLE refund_requests
    ADD CONSTRAINT fk_refund_transaction FOREIGN KEY (transaction_id)
        REFERENCES transactions(id) NOT VALID,
    ADD CONSTRAINT fk_refund_branch FOREIGN KEY (branch_id)
        REFERENCES branches(id) NOT VALID,
    ADD CONSTRAINT fk_refund_requested_by FOREIGN KEY (requested_by)
        REFERENCES users(id) NOT VALID,
    ADD CONSTRAINT fk_refund_approved_by FOREIGN KEY (approved_by)
        REFERENCES users(id) NOT VALID;
