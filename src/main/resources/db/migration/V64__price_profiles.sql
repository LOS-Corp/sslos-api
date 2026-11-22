-- Profile giá thuê theo máy. Chọn khi đưa máy vào hoạt động; lượt đặt chỉ gồm máy giặt/sấy mà mọi máy đều có
-- profile thì giá = tổng giá profile của các máy. Còn lại vẫn tính theo giá dịch vụ (service_offerings).
CREATE TABLE price_profiles (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(80)   NOT NULL,
    machine_type VARCHAR(20)   NOT NULL,
    -- Phần cố định của mỗi lượt.
    base_price   NUMERIC(10, 2) NOT NULL DEFAULT 0,
    -- Giá theo kg khách khai khi đặt; null = không tính theo kg.
    price_per_kg NUMERIC(10, 2),
    description  VARCHAR(300),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_price_profiles_type CHECK (machine_type IN ('WASHER', 'DRYER')),
    CONSTRAINT chk_price_profiles_base CHECK (base_price >= 0),
    CONSTRAINT chk_price_profiles_per_kg CHECK (price_per_kg IS NULL OR price_per_kg >= 0),
    CONSTRAINT chk_price_profiles_charges CHECK (base_price > 0 OR COALESCE(price_per_kg, 0) > 0)
);
CREATE UNIQUE INDEX uq_price_profiles_name ON price_profiles (lower(name));

ALTER TABLE machines
    ADD COLUMN price_profile_id UUID REFERENCES price_profiles (id) ON DELETE RESTRICT;

-- Giá/kg đã áp dụng khi giữ chỗ (theo profile máy giặt); mua thêm kg dùng đúng giá này. Null = giá/kg của dịch vụ.
ALTER TABLE scheduled_bookings
    ADD COLUMN price_per_kg NUMERIC(10, 2);
