-- =============================================================================
-- V4: Tên hiển thị của máy (khách thấy khi chọn máy, ví dụ "Máy giặt cửa sổ"). NULL = chưa đặt, màn hình dùng số hiệu.
-- =============================================================================

ALTER TABLE machines
    ADD COLUMN display_name VARCHAR(60) CHECK (display_name IS NULL OR btrim(display_name) <> '');
