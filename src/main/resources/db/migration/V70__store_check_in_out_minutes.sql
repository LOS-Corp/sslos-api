-- V70: thời gian check-in, check-out và chuyển đồ là cài đặt của cửa hàng, chủ cửa hàng chỉnh trên web
-- (Cửa hàng → Lịch và dọn máy), thay cho số ghi cứng trên từng dịch vụ (V30: 5 / 5 / 10 phút).
-- Áp dụng cho mọi dịch vụ tự phục vụ. Giặt hộ qua tủ giữ thời gian riêng trên booking_service_definitions.
--
-- * check_in_minutes: khách đến, mở khóa và cho đồ vào máy trước khi máy chạy.
-- * checkout_minutes: khách lấy đồ ra sau khi máy xong.
-- * transfer_minutes: Giặt - sấy, khách chuyển đồ từ máy giặt sang máy sấy.

ALTER TABLE store_settings
    ADD COLUMN check_in_minutes INTEGER,
    ADD COLUMN checkout_minutes INTEGER,
    ADD COLUMN transfer_minutes INTEGER;

-- Giữ đúng giá trị đang dùng.
UPDATE store_settings SET
    check_in_minutes = COALESCE((SELECT check_in_minutes FROM booking_service_definitions WHERE code = 'WASH_ONLY'), 5),
    checkout_minutes = COALESCE((SELECT checkout_minutes FROM booking_service_definitions WHERE code = 'WASH_ONLY'), 5),
    transfer_minutes = COALESCE((SELECT transfer_minutes FROM booking_service_definitions WHERE code = 'WASH_DRY'), 10);

ALTER TABLE store_settings
    ALTER COLUMN check_in_minutes SET DEFAULT 5,
    ALTER COLUMN check_in_minutes SET NOT NULL,
    ALTER COLUMN checkout_minutes SET DEFAULT 5,
    ALTER COLUMN checkout_minutes SET NOT NULL,
    ALTER COLUMN transfer_minutes SET DEFAULT 10,
    ALTER COLUMN transfer_minutes SET NOT NULL,
    ADD CONSTRAINT chk_store_check_in_minutes CHECK (check_in_minutes BETWEEN 0 AND 60),
    ADD CONSTRAINT chk_store_checkout_minutes CHECK (checkout_minutes BETWEEN 0 AND 120),
    ADD CONSTRAINT chk_store_transfer_minutes CHECK (transfer_minutes BETWEEN 0 AND 120);
