-- V68: máy giặt / máy sấy tự phục vụ chỉ tính tiền theo profile giá, như tiệm tự phục vụ (giá theo từng máy).
-- Bỏ "giá dịch vụ của cửa hàng" làm giá dự phòng cho máy. Giá dịch vụ chỉ còn cho giặt hộ (locker) và dịch vụ
-- không dùng máy (ví dụ bàn ủi). Máy không có profile giá thì không nhận đặt lịch.
--
-- Để giá khách trả không đổi, máy chưa có profile được gắn profile "Giá chuẩn máy giặt/sấy" tạo từ giá dịch vụ cũ
-- (WASH_ONLY / DRY_ONLY). Chủ cửa hàng nên kiểm tra lại các profile này.

INSERT INTO price_profiles (name, machine_type, base_price, description)
SELECT 'Giá chuẩn máy giặt', 'WASHER',
       COALESCE((SELECT NULLIF(price, 0) FROM service_offerings WHERE service_code = 'WASH_ONLY'), 30000),
       'Tạo tự động từ giá dịch vụ cũ khi chuyển sang tính tiền theo profile (V68). Kiểm tra lại giá.'
WHERE EXISTS (SELECT 1 FROM machines WHERE type = 'WASHER' AND price_profile_id IS NULL)
  AND NOT EXISTS (SELECT 1 FROM price_profiles WHERE lower(name) = lower('Giá chuẩn máy giặt'));

INSERT INTO price_profiles (name, machine_type, base_price, description)
SELECT 'Giá chuẩn máy sấy', 'DRYER',
       COALESCE((SELECT NULLIF(price, 0) FROM service_offerings WHERE service_code = 'DRY_ONLY'), 20000),
       'Tạo tự động từ giá dịch vụ cũ khi chuyển sang tính tiền theo profile (V68). Kiểm tra lại giá.'
WHERE EXISTS (SELECT 1 FROM machines WHERE type = 'DRYER' AND price_profile_id IS NULL)
  AND NOT EXISTS (SELECT 1 FROM price_profiles WHERE lower(name) = lower('Giá chuẩn máy sấy'));

UPDATE machines m SET price_profile_id = p.id
FROM price_profiles p
WHERE m.price_profile_id IS NULL AND m.type = 'WASHER'
  AND p.machine_type = 'WASHER' AND lower(p.name) = lower('Giá chuẩn máy giặt');

UPDATE machines m SET price_profile_id = p.id
FROM price_profiles p
WHERE m.price_profile_id IS NULL AND m.type = 'DRYER'
  AND p.machine_type = 'DRYER' AND lower(p.name) = lower('Giá chuẩn máy sấy');
