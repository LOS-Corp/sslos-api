-- V48: Wallet-first payment (FR-PAY-04, SC-31). A booking may be paid partly or fully from the wallet;
-- that part is a WALLET payment. If the rest (payOS) fails or the hold lapses, the wallet part is given
-- back and the payment becomes REVERSED.

ALTER TABLE payments DROP CONSTRAINT IF EXISTS chk_payment_method;
ALTER TABLE payments ADD CONSTRAINT chk_payment_method CHECK (payment_method IN ('ONLINE', 'WALLET'));

ALTER TABLE payments DROP CONSTRAINT IF EXISTS chk_payment_status;
ALTER TABLE payments ADD CONSTRAINT chk_payment_status CHECK (status IN ('PENDING', 'PAID', 'FAILED', 'CANCELLED', 'REVERSED'));
