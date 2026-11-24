-- =============================================================================
-- V5: Cổng thanh toán SePay (Payment Gateway: Merchant ID + Secret Key), dùng song song payOS.
-- Cổng đang dùng chọn bằng app.payment.gateway (payos | sepay). Phiếu thu nhớ cổng đã tạo nó; mã phiếu
-- (payos_order_code) dùng chung một dãy số: với SePay, order_invoice_number = "SSLOS" + mã.
-- =============================================================================

ALTER TABLE payment_intents
    ADD COLUMN gateway VARCHAR(10) CHECK (gateway IN ('PAYOS', 'SEPAY'));

UPDATE payment_intents SET gateway = 'PAYOS' WHERE payos_order_code IS NOT NULL;

ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_method_check;
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_check2;
ALTER TABLE payments
    ADD CONSTRAINT payments_method_check CHECK (method IN ('WALLET', 'PAYOS', 'SEPAY')),
    ADD CONSTRAINT payments_gateway_reference_check CHECK (method = 'WALLET' OR provider_reference IS NOT NULL);
