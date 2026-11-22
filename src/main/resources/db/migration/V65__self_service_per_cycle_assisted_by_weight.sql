-- Tự phục vụ không còn tính theo kg: mỗi lượt một giá cố định. Chỉ giặt hộ qua locker tính theo kg:
-- khách trả phần cố định khi đặt, nhân viên cân đồ rồi hệ thống thu phần theo kg trước khi khách lấy đồ.

-- 1. Dịch vụ tự phục vụ: giá/kg cũ thành giá mỗi lượt (chủ cửa hàng nên kiểm tra lại giá).
UPDATE service_offerings o
SET price = CASE WHEN o.price > 0 THEN o.price ELSE COALESCE(o.price_per_kg, o.price) END,
    price_per_kg = NULL
FROM booking_service_definitions d
WHERE d.code = o.service_code AND d.service_mode = 'SELF_SERVICE';

-- 2. Profile giá máy: chỉ còn giá mỗi lượt.
UPDATE price_profiles SET base_price = price_per_kg WHERE base_price = 0 AND price_per_kg IS NOT NULL;
ALTER TABLE price_profiles
    DROP CONSTRAINT IF EXISTS chk_price_profiles_charges,
    DROP CONSTRAINT IF EXISTS chk_price_profiles_per_kg,
    DROP COLUMN price_per_kg;
ALTER TABLE price_profiles
    ADD CONSTRAINT chk_price_profiles_positive CHECK (base_price > 0);

-- 3. Giặt hộ: tiền theo kg sau khi nhân viên cân.
CREATE TABLE booking_weight_charges (
    booking_id     UUID          PRIMARY KEY REFERENCES scheduled_bookings (id) ON DELETE CASCADE,
    -- Giao dịch khách cần trả; null khi không phải trả gì (WAIVED).
    transaction_id UUID          UNIQUE REFERENCES transactions (id),
    weight_kg      NUMERIC(6, 2) NOT NULL,
    price_per_kg   NUMERIC(10, 2) NOT NULL,
    amount         NUMERIC(10, 2) NOT NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    weighed_by     UUID,
    weighed_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    paid_at        TIMESTAMPTZ,
    CONSTRAINT chk_weight_charges_kg CHECK (weight_kg > 0),
    CONSTRAINT chk_weight_charges_amount CHECK (amount >= 0),
    CONSTRAINT chk_weight_charges_status CHECK (status IN ('PENDING', 'PAID', 'WAIVED'))
);
