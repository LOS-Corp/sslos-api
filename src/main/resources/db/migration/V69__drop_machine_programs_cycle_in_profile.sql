-- V69: chương trình giặt / sấy là việc của firmware, không phải của backend.
--
-- * Bỏ danh mục chương trình (machine_programs), ảnh chụp chương trình trên lượt đặt và chế độ chạy demo
--   (program.time_divisor). Máy giặt chạy chương trình có sẵn trong firmware.
-- * Profile giá có thêm thời gian một lượt (cycle_minutes): máy giặt dùng để giữ chỗ; máy sấy chạy đúng số phút
--   này (gói sấy cơ bản, như gói 35 phút của tiệm tự phục vụ), khách mua thêm phút khi cần.
--   Giá trị ban đầu lấy từ chương trình mặc định cũ của từng loại máy.

ALTER TABLE price_profiles ADD COLUMN cycle_minutes INTEGER;

UPDATE price_profiles p
SET cycle_minutes = COALESCE(
        (SELECT main_minutes + finish_minutes FROM machine_programs
         WHERE machine_type = p.machine_type AND is_default AND active LIMIT 1),
        CASE p.machine_type WHEN 'DRYER' THEN 45 ELSE 35 END);

ALTER TABLE price_profiles
    ALTER COLUMN cycle_minutes SET NOT NULL,
    ADD CONSTRAINT chk_price_profiles_cycle CHECK (cycle_minutes BETWEEN 1 AND 240);

DROP TABLE machine_programs;
DELETE FROM app_settings WHERE setting_key = 'program.time_divisor';
ALTER TABLE scheduled_bookings DROP COLUMN programs;
