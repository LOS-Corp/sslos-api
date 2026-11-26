-- =============================================================================
-- V5: Chức năng quản lý của Quản trị viên và Chủ cửa hàng.
--   Admin: khóa quyền đặt lịch (vi phạm), lịch sử đăng nhập, sự cố hệ thống.
--   Owner: dịch vụ tại quầy (ủi, giặt-sấy-ủi), giá theo công suất, phân công việc cho nhân viên,
--          chi phí mỗi lượt máy (ước tính lợi nhuận).
-- =============================================================================

-- ---- Khóa quyền đặt lịch (Admin xử lý vi phạm) --------------------------------------------------
-- Khác khóa tài khoản: khách vẫn đăng nhập, xem đơn, dùng ví; chỉ không đặt trước được tới booking_banned_until.
ALTER TABLE users
    ADD COLUMN booking_banned_until TIMESTAMPTZ,
    ADD COLUMN booking_ban_reason   VARCHAR(255),
    ADD COLUMN booking_banned_by    UUID REFERENCES users (id),
    ADD CONSTRAINT ck_users_booking_ban CHECK (booking_banned_until IS NULL OR booking_ban_reason IS NOT NULL);

-- ---- Lịch sử đăng nhập (cả lần sai mật khẩu) -----------------------------------------------------
CREATE TABLE login_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id        UUID REFERENCES users (id) ON DELETE SET NULL,
    email          VARCHAR(254) NOT NULL,
    success        BOOLEAN      NOT NULL,
    failure_reason VARCHAR(30)  CHECK (failure_reason IN ('UNKNOWN_EMAIL', 'WRONG_PASSWORD', 'ACCOUNT_LOCKED')),
    ip_address     VARCHAR(45),
    user_agent     VARCHAR(255),
    occurred_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (success = (failure_reason IS NULL))
);
CREATE INDEX ix_login_events_time ON login_events (occurred_at DESC);
CREATE INDEX ix_login_events_user ON login_events (user_id, occurred_at DESC);
CREATE TRIGGER trg_login_events_append_only
    BEFORE UPDATE OR DELETE ON login_events
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- ---- Sự cố cấp hệ thống (Admin) -------------------------------------------------------------------
CREATE SEQUENCE incident_code_seq START WITH 1;

CREATE TABLE incidents (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(12)   NOT NULL UNIQUE,
    title       VARCHAR(160)  NOT NULL CHECK (btrim(title) <> ''),
    description VARCHAR(2000),
    category    VARCHAR(10)   NOT NULL CHECK (category IN ('SYSTEM', 'SECURITY', 'DEVICE', 'PAYMENT', 'DATA', 'OTHER')),
    severity    VARCHAR(8)    NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status      VARCHAR(14)   NOT NULL DEFAULT 'OPEN'
                CHECK (status IN ('OPEN', 'INVESTIGATING', 'RESOLVED', 'CLOSED')),
    reported_by UUID          NOT NULL REFERENCES users (id),
    assignee_id UUID REFERENCES users (id),
    resolution  VARCHAR(2000),
    occurred_at TIMESTAMPTZ   NOT NULL,
    resolved_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version     BIGINT        NOT NULL DEFAULT 0,
    CHECK (status NOT IN ('RESOLVED', 'CLOSED') OR (resolution IS NOT NULL AND resolved_at IS NOT NULL))
);
CREATE INDEX ix_incidents_open ON incidents (severity, occurred_at DESC) WHERE status IN ('OPEN', 'INVESTIGATING');

-- Diễn biến xử lý: ghi chú và mỗi lần đổi trạng thái. Chỉ thêm, không sửa.
CREATE TABLE incident_notes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    incident_id UUID          NOT NULL REFERENCES incidents (id) ON DELETE CASCADE,
    author_id   UUID          NOT NULL REFERENCES users (id),
    kind        VARCHAR(8)    NOT NULL CHECK (kind IN ('NOTE', 'STATUS')),
    body        VARCHAR(2000) NOT NULL CHECK (btrim(body) <> ''),
    from_status VARCHAR(14),
    to_status   VARCHAR(14),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_incident_notes_incident ON incident_notes (incident_id, created_at);
CREATE TRIGGER trg_incident_notes_append_only
    BEFORE UPDATE ON incident_notes
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

-- ---- Phân công việc cho nhân viên (Owner) --------------------------------------------------------
CREATE TABLE staff_tasks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title           VARCHAR(160)  NOT NULL CHECK (btrim(title) <> ''),
    description     VARCHAR(1000),
    assignee_id     UUID          NOT NULL REFERENCES users (id),
    priority        VARCHAR(8)    NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),
    status          VARCHAR(12)   NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    due_at          TIMESTAMPTZ,
    machine_id      UUID REFERENCES machines (id),
    created_by      UUID          NOT NULL REFERENCES users (id),
    completed_at    TIMESTAMPTZ,
    completion_note VARCHAR(500),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CHECK ((status = 'DONE') = (completed_at IS NOT NULL))
);
CREATE INDEX ix_staff_tasks_assignee ON staff_tasks (assignee_id, status, due_at);

-- ---- Dịch vụ tại quầy: ủi và giặt-sấy-ủi (nhân viên làm, tính theo kg) -------------------------
-- Khách mang đồ tới quầy; nhân viên cân, thu tiền (tiền mặt / chuyển khoản), làm xong thì khách tới lấy.
ALTER TABLE store_settings
    ADD COLUMN ironing_enabled            BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN wash_dry_iron_enabled      BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN ironing_price_per_kg       BIGINT       NOT NULL DEFAULT 15000 CHECK (ironing_price_per_kg >= 1000),
    ADD COLUMN wash_dry_iron_price_per_kg BIGINT       NOT NULL DEFAULT 35000 CHECK (wash_dry_iron_price_per_kg >= 1000),
    ADD COLUMN counter_min_weight_kg      NUMERIC(4,1) NOT NULL DEFAULT 2.0   CHECK (counter_min_weight_kg > 0),
    ADD COLUMN counter_turnaround_hours   INTEGER      NOT NULL DEFAULT 24    CHECK (counter_turnaround_hours BETWEEN 1 AND 168),
    -- Chi phí trung bình một lượt máy (điện, nước, hóa chất): chỉ để ước tính lợi nhuận trong báo cáo.
    ADD COLUMN wash_cost_per_run_vnd      BIGINT       NOT NULL DEFAULT 0     CHECK (wash_cost_per_run_vnd >= 0),
    ADD COLUMN dry_cost_per_run_vnd       BIGINT       NOT NULL DEFAULT 0     CHECK (dry_cost_per_run_vnd >= 0);

CREATE SEQUENCE service_job_code_seq START WITH 1;

CREATE TABLE service_jobs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code               VARCHAR(12)   NOT NULL UNIQUE,
    service            VARCHAR(14)   NOT NULL CHECK (service IN ('IRON', 'WASH_DRY_IRON')),
    customer_name      VARCHAR(120)  NOT NULL CHECK (btrim(customer_name) <> ''),
    customer_phone     VARCHAR(16)   NOT NULL CHECK (customer_phone ~ '^\+[1-9][0-9]{7,14}$'),
    weight_kg          NUMERIC(6,2)  NOT NULL CHECK (weight_kg > 0),
    billable_weight_kg NUMERIC(6,1)  NOT NULL CHECK (billable_weight_kg > 0),
    price_per_kg       BIGINT        NOT NULL CHECK (price_per_kg > 0),
    amount             BIGINT        NOT NULL CHECK (amount > 0),
    payment_method     VARCHAR(10)   CHECK (payment_method IN ('CASH', 'TRANSFER')),
    paid_at            TIMESTAMPTZ,
    status             VARCHAR(12)   NOT NULL DEFAULT 'RECEIVED'
                       CHECK (status IN ('RECEIVED', 'PROCESSING', 'READY', 'PICKED_UP', 'CANCELLED')),
    note               VARCHAR(500),
    due_at             TIMESTAMPTZ   NOT NULL,
    received_by        UUID          NOT NULL REFERENCES users (id),
    assignee_id        UUID REFERENCES users (id),
    ready_at           TIMESTAMPTZ,
    picked_up_at       TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    cancel_reason      VARCHAR(255),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CHECK ((paid_at IS NULL) = (payment_method IS NULL)),
    CHECK (status <> 'PICKED_UP' OR paid_at IS NOT NULL),
    CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL))
);
CREATE INDEX ix_service_jobs_open ON service_jobs (due_at) WHERE status IN ('RECEIVED', 'PROCESSING', 'READY');
CREATE INDEX ix_service_jobs_paid ON service_jobs (paid_at) WHERE paid_at IS NOT NULL;
CREATE INDEX ix_service_jobs_phone ON service_jobs (customer_phone);

-- ---- Giá thuê máy theo công suất -------------------------------------------------------------------
-- Profile có capacity_kg thì chỉ gắn được cho máy đúng công suất đó; NULL = mọi công suất.
ALTER TABLE price_profiles
    ADD COLUMN capacity_kg NUMERIC(4,1) CHECK (capacity_kg IS NULL OR capacity_kg BETWEEN 1 AND 99.9);
