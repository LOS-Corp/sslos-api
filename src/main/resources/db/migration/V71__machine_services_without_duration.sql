-- V71: máy giặt / máy sấy giữ chỗ theo thời gian một lượt của chính máy khách chọn (profile giá của máy), không theo
-- thời lượng của dịch vụ. Xóa thời lượng ghi cứng của Giặt, Sấy, Giặt - sấy (35 / 45 / 35 + 45 từ V30) để không ai
-- nhầm. Ủi và Giặt hộ không chạy trên máy giặt/sấy nên giữ thời lượng của dịch vụ.

ALTER TABLE booking_service_definitions
    ALTER COLUMN processing_minutes DROP NOT NULL,
    ALTER COLUMN secondary_processing_minutes DROP NOT NULL,
    DROP CONSTRAINT IF EXISTS booking_service_definitions_processing_minutes_check,
    DROP CONSTRAINT IF EXISTS booking_service_definitions_secondary_processing_minutes_check,
    ADD CONSTRAINT chk_service_processing_minutes CHECK (processing_minutes IS NULL OR processing_minutes > 0),
    ADD CONSTRAINT chk_service_secondary_minutes CHECK (secondary_processing_minutes IS NULL OR secondary_processing_minutes >= 0);

UPDATE booking_service_definitions d
SET processing_minutes = NULL, secondary_processing_minutes = NULL
WHERE d.service_mode = 'SELF_SERVICE'
  AND NOT EXISTS (SELECT 1 FROM booking_service_requirements r
                  WHERE r.service_code = d.code AND r.resource_type NOT IN ('WASHER', 'DRYER'));

UPDATE service_offerings o
SET processing_minutes_override = NULL, secondary_processing_minutes_override = NULL
FROM booking_service_definitions d
WHERE d.code = o.service_code AND d.processing_minutes IS NULL;
