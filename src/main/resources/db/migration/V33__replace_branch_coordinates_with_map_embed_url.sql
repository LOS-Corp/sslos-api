-- V33: Replace latitude/longitude with Google Maps embed URL
-- Admin nhập URL embed Google Maps (iframe) cho từng chi nhánh thay vì tọa độ thủ công.
-- Cột cũ bị xóa vĩnh viễn - dữ liệu tọa độ cũ (nếu có) sẽ bị mất.

ALTER TABLE branches
    DROP COLUMN IF EXISTS latitude,
    DROP COLUMN IF EXISTS longitude,
    ADD COLUMN IF NOT EXISTS map_embed_url VARCHAR(500);

-- Xóa các constraint check cũ (nếu còn)
ALTER TABLE branches
    DROP CONSTRAINT IF EXISTS chk_branch_latitude,
    DROP CONSTRAINT IF EXISTS chk_branch_longitude;
