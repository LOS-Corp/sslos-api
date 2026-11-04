-- V54: Money timestamps in UTC, refund rows for every past automatic refund, retired values.

-- ── 1. BR-30 / NFR-11: money timestamps are absolute instants ────────────────
-- The old columns held UTC wall-clock values, except payments written by a JVM outside UTC.
-- Deployed containers run in UTC, so UTC is the only reading that keeps their history right.
ALTER TABLE payments
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN paid_at    TYPE TIMESTAMPTZ USING paid_at    AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE transactions
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
    ALTER COLUMN expires_at TYPE TIMESTAMPTZ USING expires_at AT TIME ZONE 'UTC',
    ALTER COLUMN paid_at    TYPE TIMESTAMPTZ USING paid_at    AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC';

ALTER TABLE refund_requests
    ALTER COLUMN created_at   TYPE TIMESTAMPTZ USING created_at   AT TIME ZONE 'UTC',
    ALTER COLUMN updated_at   TYPE TIMESTAMPTZ USING updated_at   AT TIME ZONE 'UTC',
    ALTER COLUMN approved_at  TYPE TIMESTAMPTZ USING approved_at  AT TIME ZONE 'UTC',
    ALTER COLUMN completed_at TYPE TIMESTAMPTZ USING completed_at AT TIME ZONE 'UTC';

ALTER TABLE service_types
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';

-- ── 2. A transaction tracks payment only; booking progress is on the booking ──
UPDATE transactions SET status = 'PAID' WHERE status IN ('CONFIRMED', 'COMPLETED');
ALTER TABLE transactions DROP CONSTRAINT chk_transaction_status;
ALTER TABLE transactions ADD CONSTRAINT chk_transaction_status
    CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'CANCELLED', 'EXPIRED'));

-- ── 3. BR-14: past automatic refunds count against the refund cap ─────────────
-- They were credited to the wallet with no refund_requests row, so an exception refund could repeat them.
-- Each one is spread over the paid payments of its order, within what each payment can still take.
DO $$
DECLARE
    entry     RECORD;
    payment   RECORD;
    remaining NUMERIC(10, 2);
    available NUMERIC(10, 2);
    part      NUMERIC(10, 2);
BEGIN
    FOR entry IN
        SELECT e.amount_vnd, e.reference, e.description, e.created_at, b.transaction_id, b.branch_id, w.customer_id
        FROM wallet_entries e
        JOIN wallets w ON w.id = e.wallet_id
        JOIN scheduled_bookings b
          ON (e.reference LIKE 'booking-cancel:%' AND b.id::text = substring(e.reference FROM 16))
          OR (e.reference LIKE 'no-show:%' AND b.id::text = substring(e.reference FROM 9))
          OR (e.reference LIKE 'late-payment:%' AND b.transaction_id::text = substring(e.reference FROM 14))
        WHERE e.type IN ('REFUND_CREDIT', 'LATE_PAYMENT_CREDIT')
        ORDER BY e.created_at
    LOOP
        remaining := entry.amount_vnd;
        FOR payment IN
            SELECT p.id, p.amount FROM payments p
            WHERE p.transaction_id = entry.transaction_id AND p.status = 'PAID'
            ORDER BY p.created_at DESC
            FOR UPDATE
        LOOP
            EXIT WHEN remaining <= 0;
            SELECT payment.amount - COALESCE(SUM(r.amount), 0) INTO available
            FROM refund_requests r
            WHERE r.payment_id = payment.id AND r.status NOT IN ('REJECTED', 'FAILED');
            part := LEAST(remaining, available);
            CONTINUE WHEN part <= 0;
            INSERT INTO refund_requests (id, payment_id, transaction_id, branch_id, requested_by, amount, reason,
                                         status, created_at, updated_at, completed_at)
            VALUES (gen_random_uuid(), payment.id, entry.transaction_id, entry.branch_id, entry.customer_id, part,
                    COALESCE(entry.description, entry.reference), 'COMPLETED',
                    entry.created_at, entry.created_at, entry.created_at);
            remaining := remaining - part;
        END LOOP;
        IF remaining > 0 THEN
            RAISE WARNING 'V54: % VND of wallet entry % exceeds what its order paid', remaining, entry.reference;
        END IF;
    END LOOP;
END $$;

-- ── 4. BR-20: no self-service ironing; ironing is done by Staff on assisted orders ─
UPDATE branch_service_offerings SET enabled = FALSE WHERE service_code = 'IRON_ONLY';
UPDATE booking_service_definitions SET active = FALSE WHERE code = 'IRON_ONLY';
