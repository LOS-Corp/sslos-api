-- Danh mục hãng máy và model (CRUD). Duyệt thiết bị chọn model từ danh mục thay vì gõ tay.
CREATE TABLE machine_brands (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(60)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uq_machine_brands_name ON machine_brands (lower(name));

CREATE TABLE machine_models (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id     UUID          NOT NULL REFERENCES machine_brands (id) ON DELETE RESTRICT,
    name         VARCHAR(100)  NOT NULL,
    -- Loại máy của model; null = chưa rõ.
    machine_type VARCHAR(20),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_machine_models_type CHECK (machine_type IS NULL OR machine_type IN ('WASHER', 'DRYER'))
);
CREATE UNIQUE INDEX uq_machine_models_name ON machine_models (brand_id, lower(name));

-- Thiết bị trỏ tới model trong danh mục; xóa model đang dùng bị chặn.
ALTER TABLE iot_devices
    ADD COLUMN model_id UUID REFERENCES machine_models (id) ON DELETE RESTRICT;

-- Hãng có sẵn.
INSERT INTO machine_brands (name) VALUES ('Aimi'), ('LG'), ('Samsung'), ('Electrolux'), ('Panasonic'), ('Toshiba');

-- Hãng / model đã gõ tay khi duyệt (V62) đưa vào danh mục và nối lại thiết bị.
INSERT INTO machine_brands (name)
SELECT DISTINCT trim(d.manufacturer) FROM iot_devices d
WHERE d.manufacturer IS NOT NULL AND trim(d.manufacturer) <> ''
  AND NOT EXISTS (SELECT 1 FROM machine_brands b WHERE lower(b.name) = lower(trim(d.manufacturer)));

INSERT INTO machine_models (brand_id, name)
SELECT DISTINCT b.id, trim(d.model) FROM iot_devices d
JOIN machine_brands b ON lower(b.name) = lower(trim(d.manufacturer))
WHERE d.model IS NOT NULL AND trim(d.model) <> ''
ON CONFLICT DO NOTHING;

UPDATE iot_devices d SET model_id = m.id
FROM machine_models m JOIN machine_brands b ON b.id = m.brand_id
WHERE lower(b.name) = lower(trim(d.manufacturer)) AND lower(m.name) = lower(trim(d.model));
