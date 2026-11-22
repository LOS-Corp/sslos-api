-- Duyệt thiết bị phải xác nhận thông tin tài sản: hãng, model, giá mua, bảo hành, thông số kỹ thuật.
-- Thiết bị đã duyệt trước đây để trống; lần duyệt lại sau sẽ bắt nhập.
ALTER TABLE iot_devices
    ADD COLUMN manufacturer    VARCHAR(60),
    ADD COLUMN model           VARCHAR(100),
    ADD COLUMN purchase_cost   NUMERIC(12, 2),
    ADD COLUMN warranty_until  DATE,
    ADD COLUMN technical_specs VARCHAR(500);

ALTER TABLE iot_devices
    ADD CONSTRAINT chk_iot_devices_purchase_cost CHECK (purchase_cost IS NULL OR purchase_cost >= 0);
