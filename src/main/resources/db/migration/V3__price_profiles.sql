-- =============================================================================
-- V3: Profile giá thuê — mức giá dùng chung cho nhiều máy cùng loại (giá một lượt + số phút một lượt).
-- machines.price_vnd / cycle_minutes vẫn là giá đang bán của máy (luồng đặt lượt đọc ở đó, lượt đã đặt chụp lại giá vào
-- order_legs). Máy gắn profile thì hai cột này luôn bằng profile: sửa profile là cập nhật mọi máy gắn nó trong cùng giao
-- dịch. Đổi giá/số phút riêng một máy thì máy tách khỏi profile (price_profile_id = NULL).
-- =============================================================================

CREATE TABLE price_profiles (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(80)  NOT NULL CHECK (btrim(name) <> ''),
    description   VARCHAR(300),
    machine_type  VARCHAR(10)  NOT NULL CHECK (machine_type IN ('WASHER', 'DRYER')),
    price_vnd     BIGINT       NOT NULL CHECK (price_vnd > 0),
    cycle_minutes INTEGER      NOT NULL CHECK (cycle_minutes BETWEEN 1 AND 240),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version       BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (id, machine_type)
);
CREATE UNIQUE INDEX ux_price_profiles_name ON price_profiles (lower(btrim(name)));

-- Khóa ngoại kép: máy chỉ gắn được profile cùng loại máy (NULL = không gắn, không kiểm tra).
ALTER TABLE machines
    ADD COLUMN price_profile_id UUID,
    ADD CONSTRAINT fk_machines_price_profile FOREIGN KEY (price_profile_id, machine_type)
        REFERENCES price_profiles (id, machine_type);
CREATE INDEX ix_machines_price_profile ON machines (price_profile_id);

-- Add-on bán thêm thời gian của profile máy sấy: khách đang sấy mua thêm `minutes` phút với giá `price_vnd`.
-- Máy sấy gắn profile có bộ gói (dryer_time_packs) khớp theo số phút: gói trùng số phút được cập nhật giá/bật-tắt, gói
-- không còn trong profile bị tắt (không xóa vì dryer_extensions còn trỏ tới).
CREATE TABLE price_profile_packs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    profile_id    UUID        NOT NULL REFERENCES price_profiles (id) ON DELETE CASCADE,
    minutes       INTEGER     NOT NULL CHECK (minutes BETWEEN 1 AND 120),
    price_vnd     BIGINT      NOT NULL CHECK (price_vnd > 0),
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (profile_id, minutes)
);

-- Chuyển dữ liệu: mỗi nhóm máy cùng loại, cùng giá, cùng số phút và (máy sấy) cùng bộ gói sấy thêm đang bán thành một
-- profile, gắn các máy đó vào. Hai nhóm trùng giá nhưng khác gói thì tên thêm hậu tố (2), (3)...
CREATE TEMP TABLE v3_machine_sig ON COMMIT DROP AS
SELECT m.id, m.machine_type, m.price_vnd, m.cycle_minutes,
       coalesce((SELECT string_agg(p.minutes || ':' || p.price_vnd, ',' ORDER BY p.minutes)
                 FROM dryer_time_packs p WHERE p.machine_id = m.id AND p.active), '') AS packs
FROM machines m
WHERE m.price_vnd IS NOT NULL;

CREATE TEMP TABLE v3_groups ON COMMIT DROP AS
SELECT gen_random_uuid() AS id, g.*,
       row_number() OVER (PARTITION BY g.machine_type, g.price_vnd, g.cycle_minutes ORDER BY g.packs) AS n
FROM (SELECT DISTINCT machine_type, price_vnd, cycle_minutes, packs FROM v3_machine_sig) g;

INSERT INTO price_profiles (id, name, description, machine_type, price_vnd, cycle_minutes)
SELECT id,
       CASE machine_type WHEN 'DRYER' THEN 'Sấy ' ELSE 'Giặt ' END
           || replace(to_char(price_vnd, 'FM999,999,999'), ',', '.') || 'đ · ' || cycle_minutes || ' phút'
           || CASE WHEN n > 1 THEN ' (' || n || ')' ELSE '' END,
       'Tạo tự động từ giá đang đặt trên máy',
       machine_type, price_vnd, cycle_minutes
FROM v3_groups;

UPDATE machines m
SET price_profile_id = g.id
FROM v3_machine_sig s
JOIN v3_groups g ON g.machine_type = s.machine_type AND g.price_vnd = s.price_vnd
                AND g.cycle_minutes = s.cycle_minutes AND g.packs = s.packs
WHERE m.id = s.id;

-- Các máy cùng profile có cùng bộ gói đang bán, nên DISTINCT ra đúng bộ gói của profile.
INSERT INTO price_profile_packs (profile_id, minutes, price_vnd, display_order)
SELECT DISTINCT m.price_profile_id, p.minutes, p.price_vnd, p.minutes
FROM machines m
JOIN dryer_time_packs p ON p.machine_id = m.id AND p.active
WHERE m.price_profile_id IS NOT NULL;
