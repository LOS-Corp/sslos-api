-- V67: số xung mở máy là cài đặt của máy gắn bo retrofit (máy hãng khác), không phải của cách tính tiền.
-- Profile giá chỉ còn phần tính tiền như tiệm tự phục vụ (giá mỗi lượt, máy sấy mua thêm phút). Một profile
-- dùng chung được cho máy LaundryOS và máy hãng; số xung đi theo từng máy hãng.

ALTER TABLE machines
    ADD COLUMN vend_pulses       INTEGER,
    ADD COLUMN extra_vend_pulses INTEGER,
    ADD CONSTRAINT chk_machines_vend_pulses CHECK (vend_pulses IS NULL OR vend_pulses BETWEEN 1 AND 50),
    ADD CONSTRAINT chk_machines_extra_vend_pulses
        CHECK (extra_vend_pulses IS NULL OR extra_vend_pulses BETWEEN 1 AND 50);

-- Máy đang dùng profile có số xung giữ nguyên số xung đó.
UPDATE machines m
SET vend_pulses = p.vend_pulses, extra_vend_pulses = p.extra_vend_pulses
FROM price_profiles p
WHERE m.price_profile_id = p.id AND (p.vend_pulses IS NOT NULL OR p.extra_vend_pulses IS NOT NULL);

ALTER TABLE price_profiles DROP CONSTRAINT IF EXISTS chk_price_profiles_vend_pulses;
ALTER TABLE price_profiles DROP CONSTRAINT IF EXISTS chk_price_profiles_extra;
ALTER TABLE price_profiles
    DROP COLUMN vend_pulses,
    DROP COLUMN extra_vend_pulses,
    ADD CONSTRAINT chk_price_profiles_extra CHECK (
        (extra_minutes IS NULL AND extra_price IS NULL)
        OR (extra_minutes BETWEEN 1 AND 120 AND extra_price > 0));
