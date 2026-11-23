-- =============================================================================
-- V2: Tài sản thiết bị — danh mục hãng/model máy và thông tin mua của từng bo.
-- Quản trị viên xác nhận khi duyệt bo (hoặc sửa sau): model, giá mua, hạn bảo hành, thông số. Giá mua dùng tính hoàn vốn.
-- =============================================================================

CREATE TABLE machine_brands (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name       VARCHAR(80) NOT NULL CHECK (btrim(name) <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_machine_brands_name ON machine_brands (lower(btrim(name)));

CREATE TABLE machine_models (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brand_id     UUID        NOT NULL REFERENCES machine_brands (id),
    name         VARCHAR(80) NOT NULL CHECK (btrim(name) <> ''),
    machine_type VARCHAR(10) CHECK (machine_type IN ('WASHER', 'DRYER')),   -- NULL = dùng cho cả hai / chưa rõ
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_machine_models_brand_name ON machine_models (brand_id, lower(btrim(name)));

-- Thông tin tài sản trên bo (NULL = chưa xác nhận). warranty_until NULL = không có bảo hành.
ALTER TABLE devices
    ADD COLUMN model_id          UUID REFERENCES machine_models (id),
    ADD COLUMN purchase_cost_vnd BIGINT CHECK (purchase_cost_vnd >= 0),
    ADD COLUMN warranty_until    DATE,
    ADD COLUMN technical_specs   VARCHAR(500),
    ADD COLUMN asset_updated_at  TIMESTAMPTZ;

CREATE INDEX ix_devices_model ON devices (model_id) WHERE model_id IS NOT NULL;
