-- =====================================================================================
-- LaundryOS · V1 · Lược đồ khởi tạo (refactor toàn bộ)
-- Nghiệp vụ: 00_NGHIEP_VU.md. "§x" trong comment là số mục của tài liệu đó.
--
-- Quy ước
--   * Tiền: BIGINT, đơn vị đồng, không số lẻ (§4.3).
--   * Thời điểm: TIMESTAMPTZ. Giờ trong ngày của cửa hàng: TIME theo store_settings.timezone.
--   * Enum: VARCHAR + CHECK (map JPA @Enumerated(STRING); thêm giá trị = migration mới).
--   * Khóa: UUID; bảng nhật ký dùng BIGINT identity.
--   * version: optimistic lock (JPA @Version).
--   * Cấu hình có thể đổi (§16) nên mọi đơn lưu bản chụp giá + tham số lúc đặt.
--   * Hai lượt không bao giờ chồng nhau trên một máy (§6.2): EXCLUDE constraint ở DB.
-- =====================================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- =====================================================================================
-- 1. TÀI KHOẢN (§2)
-- =====================================================================================

CREATE TABLE users (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name         VARCHAR(120) NOT NULL,
    email             VARCHAR(254) NOT NULL,
    phone             VARCHAR(16)  NOT NULL CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    password_hash     VARCHAR(255) NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                      CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    email_verified_at TIMESTAMPTZ,
    last_login_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE UNIQUE INDEX ux_users_phone ON users (phone);

-- Khách vãng lai không có tài khoản (không nằm ở đây).
CREATE TABLE user_roles (
    user_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(20) NOT NULL CHECK (role IN ('CUSTOMER', 'STAFF', 'OWNER', 'ADMIN')),
    PRIMARY KEY (user_id, role)
);

-- Token lưu dạng SHA-256 hex, không lưu bản gốc. family_id: phát hiện dùng lại token đã xoay.
CREATE TABLE refresh_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL UNIQUE,
    family_id   UUID        NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by UUID REFERENCES refresh_tokens (id),
    user_agent  VARCHAR(255),
    ip_address  VARCHAR(45),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);

CREATE TABLE password_reset_tokens (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_password_reset_tokens_user ON password_reset_tokens (user_id);

-- =====================================================================================
-- 2. CẤU HÌNH CỬA HÀNG (§1, §6.3, §16, §18)
-- =====================================================================================

-- Một cửa hàng => đúng một dòng (id = 1). Cột = bảng tham số §18.
-- Giữ chỗ thanh toán 5 phút (§6.1) chủ cửa hàng không chỉnh được => hằng số trong code.
CREATE TABLE store_settings (
    id                                INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    name                              VARCHAR(120) NOT NULL,
    address                           VARCHAR(255),
    phone                             VARCHAR(16),
    timezone                          VARCHAR(40)  NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',

    -- Dịch vụ đang bật (§3, §16 "ít nhất một dịch vụ bật")
    wash_enabled                      BOOLEAN  NOT NULL DEFAULT TRUE,
    dry_enabled                       BOOLEAN  NOT NULL DEFAULT TRUE,
    wash_dry_enabled                  BOOLEAN  NOT NULL DEFAULT TRUE,
    laundry_service_enabled           BOOLEAN  NOT NULL DEFAULT FALSE,

    -- Đặt trước và thời gian một lượt (§5, §6.1)
    max_advance_booking_hours         INTEGER NOT NULL DEFAULT 48  CHECK (max_advance_booking_hours BETWEEN 1 AND 720),
    early_checkin_minutes             INTEGER NOT NULL DEFAULT 15  CHECK (early_checkin_minutes BETWEEN 0 AND 120),
    grace_minutes                     INTEGER NOT NULL DEFAULT 10  CHECK (grace_minutes BETWEEN 0 AND 60),
    start_window_minutes              INTEGER NOT NULL DEFAULT 3   CHECK (start_window_minutes BETWEEN 1 AND 30),
    unload_minutes                    INTEGER NOT NULL DEFAULT 10  CHECK (unload_minutes BETWEEN 0 AND 60),
    cleanup_minutes                   INTEGER NOT NULL DEFAULT 5   CHECK (cleanup_minutes BETWEEN 0 AND 60),
    transfer_minutes                  INTEGER NOT NULL DEFAULT 10  CHECK (transfer_minutes BETWEEN 1 AND 60),
    transfer_extension_minutes        INTEGER NOT NULL DEFAULT 10  CHECK (transfer_extension_minutes BETWEEN 0 AND 60),
    advance_quota_percent             INTEGER NOT NULL DEFAULT 70  CHECK (advance_quota_percent BETWEEN 0 AND 100),
    kiosk_max_wait_minutes            INTEGER NOT NULL DEFAULT 30  CHECK (kiosk_max_wait_minutes BETWEEN 0 AND 240),
    dryer_extension_max_minutes       INTEGER NOT NULL DEFAULT 60  CHECK (dryer_extension_max_minutes BETWEEN 0 AND 240),

    -- Tiền giữ chỗ, hủy, vi phạm (§4.3, §11)
    deposit_percent                   INTEGER NOT NULL DEFAULT 20   CHECK (deposit_percent BETWEEN 0 AND 100),
    deposit_rounding_vnd              INTEGER  NOT NULL DEFAULT 1000 CHECK (deposit_rounding_vnd > 0),
    free_cancel_before_minutes        INTEGER NOT NULL DEFAULT 60   CHECK (free_cancel_before_minutes >= 0),
    free_cancel_after_booking_minutes INTEGER NOT NULL DEFAULT 10   CHECK (free_cancel_after_booking_minutes >= 0),
    violation_threshold               INTEGER NOT NULL DEFAULT 3    CHECK (violation_threshold >= 1),
    violation_window_days             INTEGER NOT NULL DEFAULT 90   CHECK (violation_window_days >= 1),
    violation_penalty_days            INTEGER NOT NULL DEFAULT 30   CHECK (violation_penalty_days >= 0),

    -- Đồ bỏ quên (§10.2)
    lost_item_free_hours              INTEGER NOT NULL DEFAULT 24 CHECK (lost_item_free_hours >= 0),
    lost_item_fee_per_day             BIGINT   NOT NULL DEFAULT 0  CHECK (lost_item_fee_per_day >= 0),
    lost_item_dispose_days            INTEGER NOT NULL DEFAULT 30 CHECK (lost_item_dispose_days >= 1),
    lost_item_notice_count            INTEGER NOT NULL DEFAULT 2  CHECK (lost_item_notice_count >= 0),

    -- Giặt hộ (§4.2, §13)
    laundry_drop_off_window_minutes   INTEGER     NOT NULL DEFAULT 60  CHECK (laundry_drop_off_window_minutes >= 1),
    laundry_min_weight_kg             NUMERIC(4,1) NOT NULL DEFAULT 3.0 CHECK (laundry_min_weight_kg > 0),
    laundry_weight_step_kg            NUMERIC(3,1) NOT NULL DEFAULT 0.5 CHECK (laundry_weight_step_kg > 0),
    laundry_standard_hours            INTEGER     NOT NULL DEFAULT 24  CHECK (laundry_standard_hours >= 1),
    laundry_express_hours             INTEGER     NOT NULL DEFAULT 6   CHECK (laundry_express_hours >= 1),
    laundry_express_surcharge_percent INTEGER     NOT NULL DEFAULT 50  CHECK (laundry_express_surcharge_percent >= 0),
    laundry_free_storage_hours        INTEGER     NOT NULL DEFAULT 48  CHECK (laundry_free_storage_hours >= 0),
    laundry_storage_fee_per_day       BIGINT       NOT NULL DEFAULT 0   CHECK (laundry_storage_fee_per_day >= 0),
    laundry_compensation_multiplier   INTEGER     NOT NULL DEFAULT 10  CHECK (laundry_compensation_multiplier >= 0),
    laundry_complaint_window_hours    INTEGER     NOT NULL DEFAULT 24  CHECK (laundry_complaint_window_hours >= 0),

    -- Hoàn tiền ngoại lệ (§15.1)
    staff_refund_limit_vnd            BIGINT NOT NULL DEFAULT 100000 CHECK (staff_refund_limit_vnd >= 0),

    updated_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by                        UUID REFERENCES users (id),
    version                           BIGINT      NOT NULL DEFAULT 0
);

-- Giờ mở cửa (OPEN) và giờ có nhân viên trực (STAFFED: §10.1, §12 "ngoài giờ có nhân viên").
-- day_of_week theo ISO: 1 = thứ Hai ... 7 = Chủ nhật. Không qua đêm; một ngày có thể nhiều khung.
CREATE TABLE store_hours (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind        VARCHAR(10) NOT NULL CHECK (kind IN ('OPEN', 'STAFFED')),
    day_of_week INTEGER    NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    opens_at    TIME        NOT NULL,
    closes_at   TIME        NOT NULL,
    CHECK (closes_at > opens_at),
    UNIQUE (kind, day_of_week, opens_at)
);

-- Ngày nghỉ / đóng cửa đột xuất (lễ, Tết). Trong khoảng này không bán lượt.
CREATE TABLE store_closures (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    starts_at  TIMESTAMPTZ  NOT NULL,
    ends_at    TIMESTAMPTZ  NOT NULL,
    reason     VARCHAR(255) NOT NULL,
    created_by UUID REFERENCES users (id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (ends_at > starts_at)
);

-- =====================================================================================
-- 3. MÁY VÀ LỊCH GIỮ MÁY (§1, §4.1, §6, §16)
-- =====================================================================================

-- admin_status là trạng thái do người đặt (Lỗi / Bảo trì / Ngừng dùng).
-- Trạng thái hiển thị (Sẵn sàng, Đã giữ, Chờ START, Đang chạy, Xong chờ lấy, Mất kết nối)
-- suy ra từ admin_status + devices.online + order_legs đang mở, không lưu trùng.
CREATE TABLE machines (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code              VARCHAR(10)  NOT NULL UNIQUE,                          -- số hiệu in trên máy
    machine_type      VARCHAR(10)  NOT NULL CHECK (machine_type IN ('WASHER', 'DRYER')),
    capacity_kg       NUMERIC(4,1) NOT NULL CHECK (capacity_kg > 0),
    cycle_minutes     INTEGER     NOT NULL CHECK (cycle_minutes BETWEEN 1 AND 240),
    price_vnd         BIGINT CHECK (price_vnd > 0),                          -- NULL = chưa có giá, không bán
    auto_dosing       BOOLEAN      NOT NULL DEFAULT FALSE,                   -- châm bột giặt tự động (§4.1)
    admin_status      VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE'
                      CHECK (admin_status IN ('ACTIVE', 'ERROR', 'MAINTENANCE', 'RETIRED')),
    status_reason     VARCHAR(255),
    status_changed_at TIMESTAMPTZ,
    status_changed_by UUID REFERENCES users (id),
    display_order     INTEGER     NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version           BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (id, machine_type),
    CHECK (admin_status IN ('ACTIVE', 'RETIRED') OR status_reason IS NOT NULL)
);

-- Gói sấy thêm, giá riêng từng máy sấy (§4.1, §8.4).
CREATE TABLE dryer_time_packs (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    machine_id    UUID        NOT NULL,
    machine_type  VARCHAR(10) NOT NULL DEFAULT 'DRYER' CHECK (machine_type = 'DRYER'),
    minutes       INTEGER    NOT NULL CHECK (minutes BETWEEN 1 AND 120),
    price_vnd     BIGINT      NOT NULL CHECK (price_vnd > 0),
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    display_order INTEGER    NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (machine_id, machine_type) REFERENCES machines (id, machine_type) ON DELETE CASCADE,
    UNIQUE (machine_id, minutes)
);

-- Dòng thời gian của từng máy: mỗi khoảng giữ máy cho một chặng của đơn (ORDER_LEG),
-- hoặc một khoảng bảo trì (MAINTENANCE, §6.3). Các khoảng đang active KHÔNG được chồng nhau.
--   ORDER_LEG: [T, T + ân hạn + bấm START + chạy + lấy đồ + dọn)  (§6.2)
--              co lại khi máy chạy sớm / xong sớm, kéo dài khi mua thêm phút sấy (§8.4).
--   Giữ chỗ chờ thanh toán (đơn HOLD) cũng là một dòng active; hết hạn thì app set active = false
--   (app nhả các dòng HOLD đã hết hạn trên máy đó ngay trong transaction trước khi tạo dòng mới).
CREATE TABLE machine_reservations (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    machine_id UUID        NOT NULL REFERENCES machines (id),
    kind       VARCHAR(12) NOT NULL CHECK (kind IN ('ORDER_LEG', 'MAINTENANCE')),
    starts_at  TIMESTAMPTZ NOT NULL,
    ends_at    TIMESTAMPTZ NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    note       VARCHAR(255),
    created_by UUID REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version    BIGINT      NOT NULL DEFAULT 0,
    CHECK (ends_at > starts_at),
    CHECK (kind <> 'MAINTENANCE' OR note IS NOT NULL),
    UNIQUE (id, machine_id),
    CONSTRAINT ex_machine_reservations_no_overlap
        EXCLUDE USING gist (machine_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
        WHERE (active)
);
CREATE INDEX ix_machine_reservations_timeline ON machine_reservations (machine_id, starts_at) WHERE active;

-- =====================================================================================
-- 4. KIOSK VÀ THIẾT BỊ IOT (§1, §2, sslos-iot/common/NetLink.h, BackendLink.h)
-- =====================================================================================

-- Kiosk gọi API bằng khóa riêng (lưu SHA-256 hex).
CREATE TABLE kiosks (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(20)  NOT NULL UNIQUE,
    name         VARCHAR(120) NOT NULL,
    api_key_hash VARCHAR(64)  NOT NULL UNIQUE,
    status       VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    last_seen_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Tủ locker giặt hộ. Khách KHÔNG thao tác trên tủ: mở ô bằng cách quét QR vé locker tại kiosk
-- (xem locker_assignments). Kiosk lỗi: nhân viên mở ô bằng device_commands (source STAFF, có lý do).
CREATE TABLE lockers (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(10)  NOT NULL UNIQUE,
    name         VARCHAR(120),
    admin_status VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE'
                 CHECK (admin_status IN ('ACTIVE', 'MAINTENANCE', 'RETIRED')),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE locker_compartments (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    locker_id        UUID        NOT NULL REFERENCES lockers (id) ON DELETE CASCADE,
    compartment_no   INTEGER    NOT NULL CHECK (compartment_no >= 1),
    admin_status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (admin_status IN ('ACTIVE', 'OUT_OF_SERVICE')),
    door_closed      BOOLEAN,                                   -- theo telemetry của tủ
    unlocked         BOOLEAN,
    state_updated_at TIMESTAMPTZ,
    UNIQUE (locker_id, compartment_no)
);

-- Bo mạch ESP32. id = "ESP-<MAC>" do bo tự tạo (LaundryCore.h macDeviceId).
-- link_state theo BackendLink.h: PENDING (chờ admin duyệt khóa) -> VERIFIED (đã duyệt, chưa gán)
--   -> BOUND (gán vào máy/tủ) ; REVOKED (thu hồi). DUPLICATE/AUTH_FAILED chỉ là phản hồi lúc chạy.
CREATE TABLE devices (
    id               VARCHAR(32)  PRIMARY KEY CHECK (id ~ '^ESP-[0-9A-F]{12}$'),
    device_type      VARCHAR(10)  NOT NULL CHECK (device_type IN ('WASHER', 'DRYER', 'LOCKER')),
    firmware_version VARCHAR(40),
    public_key       TEXT         NOT NULL,                      -- ECDSA P-256, base64
    key_fingerprint  VARCHAR(20)  NOT NULL,                      -- "A1B2-C3D4", hiện trên màn bo
    link_state       VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                     CHECK (link_state IN ('PENDING', 'VERIFIED', 'BOUND', 'REVOKED')),
    machine_id       UUID UNIQUE REFERENCES machines (id),
    locker_id        UUID UNIQUE REFERENCES lockers (id),
    approved_by      UUID REFERENCES users (id),
    approved_at      TIMESTAMPTZ,
    revoked_by       UUID REFERENCES users (id),
    revoked_at       TIMESTAMPTZ,
    revoke_reason    VARCHAR(255),
    online           BOOLEAN      NOT NULL DEFAULT FALSE,        -- theo topic presence (retained + last will)
    last_seen_at     TIMESTAMPTZ,
    last_ip          VARCHAR(45),
    last_rssi        INTEGER,
    last_status      JSONB,                                      -- bản status/telemetry gần nhất
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version          BIGINT       NOT NULL DEFAULT 0,
    CHECK (machine_id IS NULL OR locker_id IS NULL),
    CHECK ((link_state = 'BOUND') = (machine_id IS NOT NULL OR locker_id IS NOT NULL)),
    CHECK (locker_id IS NULL OR device_type = 'LOCKER'),
    CHECK (machine_id IS NULL OR device_type IN ('WASHER', 'DRYER'))
);

-- Nonce cho POST /api/devices/register/challenge (REGISTER) và down/challenge (AUTH). Dùng một lần.
CREATE TABLE device_challenges (
    nonce      VARCHAR(64) PRIMARY KEY,
    device_id  VARCHAR(32) NOT NULL,                             -- bo có thể chưa có trong devices
    purpose    VARCHAR(10) NOT NULL CHECK (purpose IN ('REGISTER', 'AUTH')),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_device_challenges_device ON device_challenges (device_id, created_at DESC);

-- Tài khoản broker. EMQX đọc bảng này để xác thực (docker/emqx/emqx.conf):
--   password_hash = hex(SHA-256(salt || password)).
-- Mỗi bo một tài khoản (username = client id = deviceId). Backend là superuser.
-- Role DB "emqx_auth" chỉ được SELECT bảng này (cấp ở cuối file).
CREATE TABLE mqtt_accounts (
    username      VARCHAR(64) PRIMARY KEY,
    password_hash VARCHAR(64) NOT NULL,
    salt          VARCHAR(32) NOT NULL,
    is_superuser  BOOLEAN     NOT NULL DEFAULT FALSE,
    device_id     VARCHAR(32) UNIQUE REFERENCES devices (id) ON DELETE CASCADE,
    enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (is_superuser OR device_id IS NOT NULL),
    CHECK (device_id IS NULL OR device_id = username)
);

-- Tin nhắn bo gửi lên (up/<channel>, presence). Nhật ký kỹ thuật, có thể dọn định kỳ.
CREATE TABLE device_messages (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id    VARCHAR(32) NOT NULL,
    channel      VARCHAR(30) NOT NULL,                           -- hello, status, telemetry, event, auth, command/ack...
    message_type VARCHAR(40),
    payload      JSONB,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_device_messages_device ON device_messages (device_id, received_at DESC);

-- =====================================================================================
-- 5. ĐƠN (§5, §7, §8, §11)
-- =====================================================================================

-- Một đơn = một lần khách mua dịch vụ.
--   channel APP   = đơn đặt trước (bắt buộc có tài khoản)
--   channel KIOSK = đơn đặt ngay (thường là khách vãng lai, trả 100%)
-- QR chỉ chứa mã ngẫu nhiên; DB lưu SHA-256 của mã đó (§7.4). Mất điện thoại: code + 4 số cuối SĐT (§8.1).
CREATE TABLE orders (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code                CHAR(6)     NOT NULL UNIQUE CHECK (code ~ '^[A-HJ-NP-Z2-9]{6}$'),
    qr_token_hash       VARCHAR(64) NOT NULL UNIQUE,
    channel             VARCHAR(6)  NOT NULL CHECK (channel IN ('APP', 'KIOSK')),
    service             VARCHAR(10) NOT NULL CHECK (service IN ('WASH', 'DRY', 'WASH_DRY', 'LAUNDRY')),
    customer_id         UUID REFERENCES users (id),
    guest_phone         VARCHAR(16) CHECK (guest_phone ~ '^\+[1-9][0-9]{7,14}$'),
    kiosk_id            UUID REFERENCES kiosks (id),

    status              VARCHAR(12) NOT NULL
                        CHECK (status IN ('HOLD',         -- giữ chỗ chờ thanh toán (5 phút)
                                          'CONFIRMED',    -- đã trả >= tiền giữ chỗ
                                          'IN_PROGRESS',  -- đã check-in, trả đủ, đang dùng
                                          'COMPLETED',
                                          'CANCELLED',
                                          'NO_SHOW',      -- hết ân hạn mà máy chưa chạy
                                          'EXPIRED')),    -- hết giữ chỗ mà chưa thanh toán
    scheduled_start_at  TIMESTAMPTZ NOT NULL,             -- T
    hold_expires_at     TIMESTAMPTZ,
    checkin_opens_at    TIMESTAMPTZ,                      -- T - check-in sớm
    checkin_deadline_at TIMESTAMPTZ,                      -- T + ân hạn: mốc chót check-in + trả đủ
    -- Bản chụp tham số lúc đặt (§16: đổi cấu hình không ảnh hưởng đơn đã xác nhận):
    -- ân hạn, bấm START, lấy đồ, dọn, chuyển đồ, hủy miễn phí, % giữ chỗ; với giặt hộ: giá/kg, tối thiểu...
    policy_snapshot     JSONB       NOT NULL,

    total_amount        BIGINT      NOT NULL CHECK (total_amount >= 0),
    deposit_amount      BIGINT      NOT NULL CHECK (deposit_amount >= 0),   -- tiền giữ chỗ (§4.3)
    paid_amount         BIGINT      NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    refunded_amount     BIGINT      NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0),
    forfeited_amount    BIGINT      NOT NULL DEFAULT 0 CHECK (forfeited_amount >= 0),

    reschedule_count    INTEGER    NOT NULL DEFAULT 0 CHECK (reschedule_count >= 0),
    checked_in_at       TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    cancellation_kind   VARCHAR(12) CHECK (cancellation_kind IN ('FREE', 'LATE', 'NO_SHOW', 'STORE_FAULT', 'UNPAID')),
    cancelled_by        UUID REFERENCES users (id),
    cancel_reason       VARCHAR(255),
    -- Vi phạm (§11.1): chỉ đơn đặt trước, hủy muộn hoặc không đến. Lỗi cửa hàng không tính.
    counts_as_violation BOOLEAN GENERATED ALWAYS AS
                        (channel = 'APP' AND cancellation_kind IS NOT NULL AND cancellation_kind IN ('LATE', 'NO_SHOW')) STORED,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CHECK (channel = 'KIOSK' OR customer_id IS NOT NULL),
    CHECK (channel = 'APP' OR service <> 'LAUNDRY'),
    CHECK (deposit_amount <= total_amount),
    CHECK (refunded_amount + forfeited_amount <= paid_amount),
    CHECK (status <> 'HOLD' OR hold_expires_at IS NOT NULL),
    CHECK (service = 'LAUNDRY' OR checkin_deadline_at IS NOT NULL),
    CHECK ((status IN ('CANCELLED', 'NO_SHOW', 'EXPIRED')) = (cancellation_kind IS NOT NULL))
);
CREATE INDEX ix_orders_customer ON orders (customer_id, created_at DESC);
CREATE INDEX ix_orders_guest_phone ON orders (guest_phone) WHERE guest_phone IS NOT NULL;
CREATE INDEX ix_orders_hold_expiry ON orders (hold_expires_at) WHERE status = 'HOLD';
CREATE INDEX ix_orders_checkin_deadline ON orders (checkin_deadline_at) WHERE status = 'CONFIRMED';
CREATE INDEX ix_orders_schedule ON orders (scheduled_start_at) WHERE status IN ('CONFIRMED', 'IN_PROGRESS');
CREATE INDEX ix_orders_violations ON orders (customer_id, cancelled_at) WHERE counts_as_violation;

-- Chặng sử dụng máy của đơn tự phục vụ: WASH/DRY có 1 chặng, WASH_DRY có 2 (1 = giặt, 2 = sấy).
-- Giá chặng = giá máy lúc đặt (§4.1, dùng khi hoàn một chặng).
-- machine_id luôn khớp reservation (FK kép, đổi máy ở reservation thì cascade sang đây).
CREATE TABLE order_legs (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id              UUID         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    leg_no                INTEGER     NOT NULL CHECK (leg_no IN (1, 2)),
    machine_type          VARCHAR(10)  NOT NULL CHECK (machine_type IN ('WASHER', 'DRYER')),
    machine_id            UUID         NOT NULL,
    reservation_id        UUID         NOT NULL UNIQUE,
    price_amount          BIGINT       NOT NULL CHECK (price_amount >= 0),
    cycle_minutes         INTEGER     NOT NULL CHECK (cycle_minutes > 0),
    capacity_kg           NUMERIC(4,1) NOT NULL,
    status                VARCHAR(16)  NOT NULL DEFAULT 'SCHEDULED'
                          CHECK (status IN ('SCHEDULED',        -- chờ check-in
                                            'ACTIVATED',        -- kiosk đã kích hoạt, chờ bấm START
                                            'RUNNING',          -- máy báo đang chạy thật (§9)
                                            'AWAITING_UNLOAD',  -- xong, chờ lấy đồ
                                            'DONE',
                                            'CANCELLED',
                                            'INTERRUPTED')),    -- máy hỏng / mất điện giữa chừng (§12)
    planned_start_at      TIMESTAMPTZ  NOT NULL,
    start_deadline_at     TIMESTAMPTZ,                          -- mốc chót được kích hoạt / bấm START
    activated_at          TIMESTAMPTZ,
    activation_expires_at TIMESTAMPTZ,
    activation_count      INTEGER     NOT NULL DEFAULT 0,
    started_at            TIMESTAMPTZ,
    expected_end_at       TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    done_at               TIMESTAMPTZ,
    extra_minutes         INTEGER     NOT NULL DEFAULT 0 CHECK (extra_minutes >= 0),
    stopped_by_customer   BOOLEAN      NOT NULL DEFAULT FALSE,
    interruption_reason   VARCHAR(255),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    UNIQUE (order_id, leg_no),
    FOREIGN KEY (machine_id, machine_type) REFERENCES machines (id, machine_type),
    FOREIGN KEY (reservation_id, machine_id) REFERENCES machine_reservations (id, machine_id) ON UPDATE CASCADE,
    CHECK (extra_minutes = 0 OR machine_type = 'DRYER')
);
CREATE INDEX ix_order_legs_machine_open ON order_legs (machine_id, planned_start_at)
    WHERE status IN ('SCHEDULED', 'ACTIVATED', 'RUNNING', 'AWAITING_UNLOAD');
CREATE INDEX ix_order_legs_activation ON order_legs (activation_expires_at) WHERE status = 'ACTIVATED';

-- Lịch sử vòng đời đơn (đổi trạng thái, check-in, đổi máy, đổi giờ...). Chỉ thêm, không sửa.
CREATE TABLE order_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id    UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    event_type  VARCHAR(40) NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16),
    data        JSONB,
    actor_type  VARCHAR(10) NOT NULL CHECK (actor_type IN ('USER', 'KIOSK', 'DEVICE', 'SYSTEM')),
    actor_id    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_order_events_order ON order_events (order_id, created_at);

-- =====================================================================================
-- 6. GIẶT HỘ QUA LOCKER (§4.2, §13)
-- =====================================================================================

-- Bảng giá giặt hộ: PER_KG (đúng một dòng active), SPECIAL_ITEM (chăn, mền... theo món), IRONING (theo món).
CREATE TABLE laundry_price_items (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code          VARCHAR(30)  NOT NULL UNIQUE,
    name          VARCHAR(120) NOT NULL,
    category      VARCHAR(12)  NOT NULL CHECK (category IN ('PER_KG', 'SPECIAL_ITEM', 'IRONING')),
    unit_price    BIGINT       NOT NULL CHECK (unit_price > 0),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order INTEGER     NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_laundry_price_per_kg ON laundry_price_items (category) WHERE category = 'PER_KG' AND active;

CREATE TABLE laundry_jobs (
    order_id                  UUID PRIMARY KEY REFERENCES orders (id) ON DELETE CASCADE,
    express                   BOOLEAN      NOT NULL DEFAULT FALSE,
    ironing_requested         BOOLEAN      NOT NULL DEFAULT FALSE,
    customer_note             VARCHAR(500),
    stage                     VARCHAR(20)  NOT NULL DEFAULT 'AWAITING_DROP_OFF'
                              CHECK (stage IN ('AWAITING_DROP_OFF',  -- chờ khách bỏ đồ vào locker
                                               'DROPPED_OFF',        -- đã gửi, chờ nhân viên lấy
                                               'RECEIVED',           -- đang kiểm đồ / cân
                                               'QUOTED',             -- đã báo giá cuối
                                               'PROCESSING',
                                               'READY',              -- đã cất vào locker trả đồ
                                               'PICKED_UP',
                                               'CANCELLED',
                                               'UNCLAIMED')),        -- quá hạn lưu, xử lý như đồ bỏ quên
    drop_off_deadline_at      TIMESTAMPTZ  NOT NULL,                 -- T + 60 phút
    dropped_off_at            TIMESTAMPTZ,
    received_at               TIMESTAMPTZ,
    received_by               UUID REFERENCES users (id),
    measured_weight_kg        NUMERIC(6,2) CHECK (measured_weight_kg >= 0),
    billable_weight_kg        NUMERIC(6,1) CHECK (billable_weight_kg >= 0),   -- làm tròn lên bước, >= tối thiểu
    price_per_kg              BIGINT       NOT NULL CHECK (price_per_kg > 0),  -- bản chụp lúc đặt
    express_surcharge_percent INTEGER     NOT NULL DEFAULT 0,
    minimum_amount            BIGINT       NOT NULL CHECK (minimum_amount >= 0), -- giá 3 kg, trả lúc đặt
    quoted_amount             BIGINT       CHECK (quoted_amount >= 0),
    quoted_at                 TIMESTAMPTZ,
    quoted_by                 UUID REFERENCES users (id),
    due_at                    TIMESTAMPTZ,                           -- nhận đồ + 24h / 6h
    ready_at                  TIMESTAMPTZ,
    free_storage_until        TIMESTAMPTZ,                           -- ready_at + 48h
    storage_fee_amount        BIGINT       NOT NULL DEFAULT 0 CHECK (storage_fee_amount >= 0),
    picked_up_at              TIMESTAMPTZ,
    complaint_deadline_at     TIMESTAMPTZ,                           -- picked_up_at + 24h
    staff_note                VARCHAR(1000),
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                   BIGINT       NOT NULL DEFAULT 0,
    CHECK (quoted_amount IS NULL OR quoted_amount >= minimum_amount)
);
CREATE INDEX ix_laundry_jobs_stage ON laundry_jobs (stage);

-- Dòng tính tiền sau khi cân: WEIGHT, SPECIAL_ITEM, IRONING, EXPRESS_SURCHARGE;
-- REJECTED_ITEM = món không nhận giặt, trả lại khách (amount = 0).
CREATE TABLE laundry_job_items (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      UUID          NOT NULL REFERENCES laundry_jobs (order_id) ON DELETE CASCADE,
    line_type     VARCHAR(20)   NOT NULL
                  CHECK (line_type IN ('WEIGHT', 'SPECIAL_ITEM', 'IRONING', 'EXPRESS_SURCHARGE', 'REJECTED_ITEM')),
    price_item_id UUID REFERENCES laundry_price_items (id),
    description   VARCHAR(255)  NOT NULL,
    quantity      NUMERIC(7,2)  NOT NULL CHECK (quantity > 0),
    unit_price    BIGINT        NOT NULL CHECK (unit_price >= 0),
    amount        BIGINT        NOT NULL CHECK (amount >= 0),
    created_by    UUID REFERENCES users (id),
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (line_type <> 'REJECTED_ITEM' OR amount = 0)
);
CREATE INDEX ix_laundry_job_items_order ON laundry_job_items (order_id);

-- Một lần giữ ô locker cho đơn giặt hộ, kèm VÉ LOCKER của lần đó:
--   DROP_OFF = vé gửi đồ (hiệu lực T .. T + 60 phút), PICK_UP = vé nhận đồ (từ lúc đồ xong, sau khi trả đủ).
-- Mở ô: khách quét QR vé tại kiosk -> backend kiểm vé (đúng đơn, đúng mục đích, còn hạn, đơn đã trả đủ
--   nếu là PICK_UP) -> device_commands OPEN xuống tủ. Mất điện thoại: mã đơn + 4 số cuối SĐT tại kiosk.
-- QR vé chỉ chứa mã ngẫu nhiên; DB lưu SHA-256 hex (giống orders.qr_token_hash). Vé gửi và vé nhận khác nhau.
-- Vé quét được nhiều lần trong hạn (cửa đóng mà chưa kịp bỏ/lấy đồ); hết hiệu lực khi đã dùng xong
-- (used_at) hoặc assignment bị nhả.
-- ends_at NULL = giữ đến khi nhả. Một ô không bị hai đơn giữ cùng lúc.
CREATE TABLE locker_assignments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    compartment_id    UUID        NOT NULL REFERENCES locker_compartments (id),
    order_id          UUID        NOT NULL REFERENCES laundry_jobs (order_id),
    purpose           VARCHAR(10) NOT NULL CHECK (purpose IN ('DROP_OFF', 'PICK_UP')),
    ticket_token_hash VARCHAR(64) NOT NULL UNIQUE,
    valid_from        TIMESTAMPTZ NOT NULL,
    valid_until       TIMESTAMPTZ,                       -- NULL = đến khi lấy đồ (phí lưu tính riêng)
    starts_at         TIMESTAMPTZ NOT NULL,
    ends_at           TIMESTAMPTZ,
    active            BOOLEAN     NOT NULL DEFAULT TRUE,
    open_count        INTEGER    NOT NULL DEFAULT 0,    -- số lần kiosk đã mở ô bằng vé này
    last_opened_at    TIMESTAMPTZ,
    last_closed_at    TIMESTAMPTZ,
    used_at           TIMESTAMPTZ,                       -- khách xác nhận đã bỏ / lấy đồ xong
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    released_at       TIMESTAMPTZ,
    CHECK (ends_at IS NULL OR ends_at > starts_at),
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    UNIQUE (order_id, purpose, starts_at),
    CONSTRAINT ex_locker_assignments_no_overlap
        EXCLUDE USING gist (compartment_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
        WHERE (active)
);
CREATE INDEX ix_locker_assignments_order ON locker_assignments (order_id);

-- Lệnh xuống bo (down/command). id = commandId bo gửi lại trong command/ack.
-- Lệnh của nhân viên bắt buộc có lý do (§2, §16).
-- Đích của lệnh: một chặng máy (order_leg_id) hoặc một ô locker theo vé (locker_assignment_id).
CREATE TABLE device_commands (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id            VARCHAR(32) NOT NULL REFERENCES devices (id),
    command_type         VARCHAR(30) NOT NULL,   -- AUTHORIZE_START, CANCEL_AUTHORIZATION, ADD_TIME, STOP, RESTART,
                                                 -- SELF_TEST, OPEN (mở ô locker)... (theo firmware)
    payload              JSONB       NOT NULL DEFAULT '{}'::jsonb,
    order_leg_id         UUID REFERENCES order_legs (id),
    locker_assignment_id UUID REFERENCES locker_assignments (id),
    source               VARCHAR(10) NOT NULL CHECK (source IN ('SYSTEM', 'KIOSK', 'STAFF')),
    requested_by         UUID REFERENCES users (id),
    kiosk_id             UUID REFERENCES kiosks (id),
    reason               VARCHAR(255),
    status               VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'SENT', 'ACKED', 'FAILED', 'TIMEOUT')),
    attempts             INTEGER    NOT NULL DEFAULT 0,
    expires_at           TIMESTAMPTZ,
    sent_at              TIMESTAMPTZ,
    acked_at             TIMESTAMPTZ,
    result               JSONB,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (source <> 'STAFF' OR (requested_by IS NOT NULL AND reason IS NOT NULL)),
    CHECK (source <> 'KIOSK' OR kiosk_id IS NOT NULL),
    CHECK (order_leg_id IS NULL OR locker_assignment_id IS NULL)
);
CREATE INDEX ix_device_commands_device ON device_commands (device_id, created_at DESC);
CREATE INDEX ix_device_commands_pending ON device_commands (created_at) WHERE status IN ('PENDING', 'SENT');
CREATE INDEX ix_device_commands_locker_assignment ON device_commands (locker_assignment_id)
    WHERE locker_assignment_id IS NOT NULL;

-- =====================================================================================
-- 7. TỆP ĐÍNH KÈM (ảnh kiểm đồ, ảnh đồ bỏ quên, chứng từ chuyển khoản)
-- =====================================================================================

CREATE TABLE attachments (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_type   VARCHAR(20)  NOT NULL
                 CHECK (owner_type IN ('LAUNDRY_JOB', 'FOUND_ITEM', 'WITHDRAWAL', 'REFUND', 'LAUNDRY_CLAIM')),
    owner_id     UUID         NOT NULL,
    purpose      VARCHAR(20)  NOT NULL
                 CHECK (purpose IN ('INTAKE_PHOTO', 'DAMAGE_PHOTO', 'ITEM_PHOTO', 'TRANSFER_PROOF', 'EVIDENCE')),
    storage_key  VARCHAR(500) NOT NULL UNIQUE,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT       NOT NULL CHECK (size_bytes > 0),
    sha256       VARCHAR(64)  NOT NULL,
    uploaded_by  UUID REFERENCES users (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_attachments_owner ON attachments (owner_type, owner_id);

-- =====================================================================================
-- 8. THANH TOÁN (§14)
-- =====================================================================================

-- payOS orderCode là số nguyên; lấy từ sequence để không trùng.
CREATE SEQUENCE payos_order_code_seq START WITH 100000001;

-- Phiếu thu: số tiền cố định, có hạn (§14). Ví trừ trước, phần thiếu qua payOS.
CREATE TABLE payment_intents (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id              UUID        NOT NULL REFERENCES orders (id),
    purpose               VARCHAR(20) NOT NULL
                          CHECK (purpose IN ('DEPOSIT',          -- tiền giữ chỗ đặt trước
                                             'FULL',             -- trả 100% lúc đặt (app hoặc kiosk)
                                             'BALANCE',          -- phần còn lại trước/khi check-in
                                             'DRYER_EXTENSION',
                                             'LAUNDRY_MINIMUM',
                                             'LAUNDRY_BALANCE',
                                             'STORAGE_FEE')),
    amount                BIGINT      NOT NULL CHECK (amount > 0),
    wallet_amount         BIGINT      NOT NULL DEFAULT 0 CHECK (wallet_amount >= 0),
    gateway_amount        BIGINT      NOT NULL DEFAULT 0 CHECK (gateway_amount >= 0),
    status                VARCHAR(10) NOT NULL DEFAULT 'PENDING'
                          CHECK (status IN ('PENDING', 'PAID', 'EXPIRED', 'CANCELLED', 'FAILED')),
    payos_order_code      BIGINT UNIQUE,
    payos_payment_link_id VARCHAR(64) UNIQUE,
    checkout_url          TEXT,
    qr_code               TEXT,
    expires_at            TIMESTAMPTZ NOT NULL,   -- 5 phút; check-in: không quá T + ân hạn (§8.1)
    paid_at               TIMESTAMPTZ,
    created_by            UUID REFERENCES users (id),
    kiosk_id              UUID REFERENCES kiosks (id),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    version               BIGINT      NOT NULL DEFAULT 0,
    CHECK (wallet_amount + gateway_amount = amount)
);
CREATE INDEX ix_payment_intents_order ON payment_intents (order_id);
CREATE INDEX ix_payment_intents_pending ON payment_intents (expires_at) WHERE status = 'PENDING';
-- Mỗi đơn chỉ một phiếu thu đang mở cho cùng một mục đích.
CREATE UNIQUE INDEX ux_payment_intents_open ON payment_intents (order_id, purpose)
    WHERE status = 'PENDING' AND purpose <> 'DRYER_EXTENSION';

-- Mua thêm phút sấy (§8.4). Máy không nhận lệnh => hoàn 100% gói.
CREATE TABLE dryer_extensions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_leg_id      UUID        NOT NULL REFERENCES order_legs (id),
    pack_id           UUID REFERENCES dryer_time_packs (id),
    minutes           INTEGER    NOT NULL CHECK (minutes > 0),
    price_amount      BIGINT      NOT NULL CHECK (price_amount > 0),
    status            VARCHAR(16) NOT NULL DEFAULT 'PENDING_PAYMENT'
                      CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'APPLIED', 'FAILED', 'EXPIRED')),
    payment_intent_id UUID UNIQUE REFERENCES payment_intents (id),
    device_command_id UUID REFERENCES device_commands (id),
    applied_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_dryer_extensions_leg ON dryer_extensions (order_leg_id);

-- Hộp thư webhook payOS: lưu nguyên văn, khóa duy nhất chống xử lý lặp (§14 "chỉ thu một lần").
CREATE TABLE payment_webhook_events (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider        VARCHAR(10)  NOT NULL DEFAULT 'PAYOS',
    event_key       VARCHAR(120) NOT NULL,           -- orderCode + reference
    signature_valid BOOLEAN      NOT NULL,
    payload         JSONB        NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    error           VARCHAR(500),
    UNIQUE (provider, event_key)
);

-- Tiền thực nhận (ví hoặc ngân hàng). Mỗi dòng là một biên nhận (§14).
--   CAPTURED: ghi nhận cho đơn.
--   REJECTED: tiền về nhưng không ghi nhận (muộn / sai số / phiếu đã đóng) -> tự hoàn (§15).
-- Thông tin tài khoản người chuyển dùng để hoàn cho khách vãng lai (§15.1).
CREATE TABLE payments (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_no           VARCHAR(20)  NOT NULL UNIQUE,
    order_id             UUID REFERENCES orders (id),
    payment_intent_id    UUID REFERENCES payment_intents (id),
    method               VARCHAR(10)  NOT NULL CHECK (method IN ('WALLET', 'PAYOS')),
    amount               BIGINT       NOT NULL CHECK (amount > 0),
    status               VARCHAR(10)  NOT NULL CHECK (status IN ('CAPTURED', 'REJECTED')),
    reject_reason        VARCHAR(20)  CHECK (reject_reason IN ('LATE', 'AMOUNT_MISMATCH', 'INTENT_CLOSED', 'UNKNOWN_ORDER')),
    provider_reference   VARCHAR(100),                -- mã giao dịch ngân hàng từ payOS
    payer_bank_bin       VARCHAR(10),
    payer_account_number VARCHAR(30),
    payer_account_name   VARCHAR(120),
    webhook_event_id     BIGINT REFERENCES payment_webhook_events (id),
    received_at          TIMESTAMPTZ  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (method, provider_reference),
    CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL)),
    CHECK (status <> 'CAPTURED' OR (order_id IS NOT NULL AND payment_intent_id IS NOT NULL)),
    CHECK (method <> 'PAYOS' OR provider_reference IS NOT NULL)
);
CREATE INDEX ix_payments_order ON payments (order_id);
-- Một phiếu thu chỉ ghi nhận một lần cho mỗi phương thức.
CREATE UNIQUE INDEX ux_payments_intent_captured ON payments (payment_intent_id, method) WHERE status = 'CAPTURED';

-- =====================================================================================
-- 9. VÍ (§15.2)
-- =====================================================================================

-- balance = số dư khả dụng; held_balance = đang giữ cho yêu cầu rút. Không bao giờ âm.
CREATE TABLE wallets (
    user_id      UUID PRIMARY KEY REFERENCES users (id),
    balance      BIGINT      NOT NULL DEFAULT 0 CHECK (balance >= 0),
    held_balance BIGINT      NOT NULL DEFAULT 0 CHECK (held_balance >= 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    version      BIGINT      NOT NULL DEFAULT 0
);

-- Hoàn tiền (§15.1).
--   AUTO: theo đúng quy tắc, không cần duyệt. EXCEPTION: nhân viên tạo, có lý do;
--   <= store_settings.staff_refund_limit_vnd nhân viên duyệt, lớn hơn chủ cửa hàng duyệt (app kiểm).
--   Đích: WALLET (khách có tài khoản), BANK_TRANSFER (về tài khoản đã chuyển), COUNTER (tại quầy).
--   Hoàn tiền của khoản bị REJECTED: chỉ cần payment_id. Còn lại phải có order_id.
CREATE TABLE refunds (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id               UUID REFERENCES orders (id),
    payment_id             UUID REFERENCES payments (id),
    amount                 BIGINT      NOT NULL CHECK (amount > 0),
    kind                   VARCHAR(10) NOT NULL CHECK (kind IN ('AUTO', 'EXCEPTION')),
    reason_code            VARCHAR(30) NOT NULL
                           CHECK (reason_code IN ('FREE_CANCEL',          -- hủy miễn phí
                                                  'LATE_CANCEL_EXCESS',   -- hủy muộn: hoàn phần trả thêm ngoài 20%
                                                  'NO_SHOW_EXCESS',
                                                  'KIOSK_CANCEL',         -- hủy trên kiosk trước khi bấm START
                                                  'ABANDONED_80',         -- đặt ngay bỏ không dùng: hoàn 80%
                                                  'STORE_FAULT',          -- lỗi cửa hàng: hoàn 100%
                                                  'MACHINE_DOWNGRADE',    -- đổi sang máy rẻ hơn: hoàn chênh
                                                  'DRY_LEG_CANCELLED',    -- quá hạn chuyển đồ: hoàn 80% chặng sấy
                                                  'EXTENSION_FAILED',     -- máy không nhận thêm phút
                                                  'LAUNDRY_NOT_DROPPED',  -- giặt hộ không gửi đồ: hoàn 80%
                                                  'LATE_PAYMENT',         -- tiền về sau hạn
                                                  'AMOUNT_MISMATCH',      -- tiền về sai số
                                                  'PAYMENT_ABANDONED',    -- phiếu thu hết hạn: hoàn phần đã trừ ví
                                                  'EXCEPTION')),          -- ngoại lệ, có lý do
    reason_text            VARCHAR(500),
    destination            VARCHAR(14) NOT NULL CHECK (destination IN ('WALLET', 'BANK_TRANSFER', 'COUNTER')),
    status                 VARCHAR(16) NOT NULL DEFAULT 'APPROVED'
                           CHECK (status IN ('PENDING_APPROVAL', 'APPROVED', 'COMPLETED', 'REJECTED', 'FAILED')),
    recipient_user_id      UUID REFERENCES users (id),
    bank_bin               VARCHAR(10),
    bank_account_number    VARCHAR(30),
    bank_account_name      VARCHAR(120),
    requested_by           UUID REFERENCES users (id),      -- NULL = hệ thống
    approved_by            UUID REFERENCES users (id),
    approved_at            TIMESTAMPTZ,
    rejected_reason        VARCHAR(255),
    completed_by           UUID REFERENCES users (id),
    completed_at           TIMESTAMPTZ,
    transfer_reference     VARCHAR(100),
    proof_attachment_id    UUID REFERENCES attachments (id),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    version                BIGINT      NOT NULL DEFAULT 0,
    CHECK (order_id IS NOT NULL OR payment_id IS NOT NULL),
    CHECK (kind = 'AUTO' OR (requested_by IS NOT NULL AND reason_text IS NOT NULL AND reason_code = 'EXCEPTION')),
    CHECK (destination <> 'WALLET' OR recipient_user_id IS NOT NULL),
    CHECK (status <> 'REJECTED' OR rejected_reason IS NOT NULL),
    CHECK (status NOT IN ('APPROVED', 'COMPLETED') OR kind = 'AUTO' OR approved_by IS NOT NULL)
);
CREATE INDEX ix_refunds_order ON refunds (order_id);
CREATE INDEX ix_refunds_payment ON refunds (payment_id);
CREATE INDEX ix_refunds_open ON refunds (created_at) WHERE status IN ('PENDING_APPROVAL', 'APPROVED');

-- Rút ví (§15.2): giữ tiền ngay, chủ cửa hàng chuyển trong 3 ngày làm việc, có chứng từ mới hoàn tất.
CREATE TABLE wallet_withdrawals (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID         NOT NULL REFERENCES wallets (user_id),
    amount              BIGINT       NOT NULL CHECK (amount > 0),
    bank_bin            VARCHAR(10)  NOT NULL,
    bank_name           VARCHAR(120),
    bank_account_number VARCHAR(30)  NOT NULL,
    bank_account_name   VARCHAR(120) NOT NULL,
    status              VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING', 'COMPLETED', 'REJECTED', 'CANCELLED')),
    requested_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    due_at              TIMESTAMPTZ  NOT NULL,
    processed_by        UUID REFERENCES users (id),
    processed_at        TIMESTAMPTZ,
    transfer_reference  VARCHAR(100),
    proof_attachment_id UUID REFERENCES attachments (id),
    reject_reason       VARCHAR(255),
    version             BIGINT       NOT NULL DEFAULT 0,
    CHECK (status <> 'COMPLETED' OR proof_attachment_id IS NOT NULL),
    CHECK (status <> 'REJECTED' OR reject_reason IS NOT NULL)
);
CREATE INDEX ix_wallet_withdrawals_user ON wallet_withdrawals (user_id, requested_at DESC);
CREATE INDEX ix_wallet_withdrawals_pending ON wallet_withdrawals (due_at) WHERE status = 'PENDING';

-- Khiếu nại / bồi thường giặt hộ (§13.7). Bồi thường không phải hoàn tiền (có thể vượt số đã trả),
-- nên chi qua ví (wallet_transactions COMPENSATION) hoặc chuyển khoản.
CREATE TABLE laundry_claims (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID          NOT NULL REFERENCES laundry_jobs (order_id),
    item_id             UUID REFERENCES laundry_job_items (id),
    description         VARCHAR(1000) NOT NULL,
    status              VARCHAR(10)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED')),
    compensation_amount BIGINT        CHECK (compensation_amount >= 0),   -- tối đa 10 lần giá món (app kiểm)
    payout_method       VARCHAR(14)   CHECK (payout_method IN ('WALLET', 'BANK_TRANSFER')),
    decided_by          UUID REFERENCES users (id),
    decided_at          TIMESTAMPTZ,
    decision_note       VARCHAR(500),
    created_by          UUID REFERENCES users (id),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (status <> 'APPROVED' OR (compensation_amount IS NOT NULL AND payout_method IS NOT NULL))
);
CREATE INDEX ix_laundry_claims_order ON laundry_claims (order_id);

-- Sổ cái ví: chỉ thêm, không sửa, không xóa. amount = biến động số dư khả dụng (có dấu),
-- held_delta = biến động số giữ. Đối soát hằng ngày: SUM theo user = wallets (§15.2).
CREATE TABLE wallet_transactions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES wallets (user_id),
    entry_type      VARCHAR(20)  NOT NULL
                    CHECK (entry_type IN ('REFUND', 'COMPENSATION', 'PAYMENT',
                                          'WITHDRAWAL_HOLD', 'WITHDRAWAL_RELEASE', 'WITHDRAWAL_PAYOUT')),
    amount          BIGINT       NOT NULL,
    held_delta      BIGINT       NOT NULL DEFAULT 0,
    balance_after   BIGINT       NOT NULL CHECK (balance_after >= 0),
    held_after      BIGINT       NOT NULL CHECK (held_after >= 0),
    refund_id       UUID UNIQUE REFERENCES refunds (id),
    payment_id      UUID UNIQUE REFERENCES payments (id),
    withdrawal_id   UUID REFERENCES wallet_withdrawals (id),
    claim_id        UUID UNIQUE REFERENCES laundry_claims (id),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    note            VARCHAR(255),
    created_by      UUID REFERENCES users (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (
        (entry_type = 'REFUND'             AND amount > 0 AND held_delta = 0 AND refund_id IS NOT NULL) OR
        (entry_type = 'COMPENSATION'       AND amount > 0 AND held_delta = 0 AND claim_id IS NOT NULL) OR
        (entry_type = 'PAYMENT'            AND amount < 0 AND held_delta = 0 AND payment_id IS NOT NULL) OR
        (entry_type = 'WITHDRAWAL_HOLD'    AND amount < 0 AND held_delta = -amount AND withdrawal_id IS NOT NULL) OR
        (entry_type = 'WITHDRAWAL_RELEASE' AND amount > 0 AND held_delta = -amount AND withdrawal_id IS NOT NULL) OR
        (entry_type = 'WITHDRAWAL_PAYOUT'  AND amount = 0 AND held_delta < 0 AND withdrawal_id IS NOT NULL)
    )
);
CREATE INDEX ix_wallet_transactions_user ON wallet_transactions (user_id, created_at DESC);

CREATE TABLE wallet_reconciliations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_date        DATE        NOT NULL UNIQUE,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    wallets_checked INTEGER     NOT NULL DEFAULT 0,
    mismatch_count  INTEGER     NOT NULL DEFAULT 0,
    mismatches      JSONB,
    notified_at     TIMESTAMPTZ
);

-- =====================================================================================
-- 10. ĐỒ BỎ QUÊN (§10.2)
-- =====================================================================================

CREATE TABLE found_items (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id           UUID REFERENCES orders (id),
    machine_id         UUID REFERENCES machines (id),
    description        VARCHAR(500) NOT NULL,
    storage_location   VARCHAR(120),
    status             VARCHAR(10)  NOT NULL DEFAULT 'STORED' CHECK (status IN ('STORED', 'RETURNED', 'DISPOSED')),
    found_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    found_by           UUID REFERENCES users (id),
    free_until         TIMESTAMPTZ  NOT NULL,      -- found_at + 24h
    dispose_after      TIMESTAMPTZ  NOT NULL,      -- found_at + 30 ngày
    notice_count       INTEGER     NOT NULL DEFAULT 0,
    last_notice_at     TIMESTAMPTZ,
    storage_fee_amount BIGINT       NOT NULL DEFAULT 0 CHECK (storage_fee_amount >= 0),
    returned_at        TIMESTAMPTZ,
    returned_by        UUID REFERENCES users (id),
    disposed_at        TIMESTAMPTZ,
    disposal_method    VARCHAR(10)  CHECK (disposal_method IN ('SOLD', 'DONATED', 'DISCARDED')),
    note               VARCHAR(500),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (status <> 'DISPOSED' OR disposal_method IS NOT NULL)
);
CREATE INDEX ix_found_items_stored ON found_items (dispose_after) WHERE status = 'STORED';
CREATE INDEX ix_found_items_order ON found_items (order_id);

-- =====================================================================================
-- 11. THÔNG BÁO (§17)
-- =====================================================================================

-- Thông báo hẹn giờ (nhắc trước T 30 phút...) cũng là một dòng với scheduled_at.
-- dedupe_key ví dụ "order:<id>:REMIND_BEFORE_START" để không gửi trùng.
CREATE TABLE notifications (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID REFERENCES users (id),
    phone        VARCHAR(16),
    order_id     UUID REFERENCES orders (id),
    type         VARCHAR(30) NOT NULL
                 CHECK (type IN ('ORDER_CONFIRMED', 'PAYMENT_RECEIVED', 'REMIND_BEFORE_START', 'REMIND_AT_START',
                                 'GRACE_ENDING', 'MACHINE_REASSIGNED', 'CYCLE_ENDING', 'CYCLE_DONE',
                                 'UNLOAD_OVERDUE', 'ORDER_CANCELLED', 'REFUND_ISSUED', 'LAUNDRY_QUOTED',
                                 'LAUNDRY_READY', 'FOUND_ITEM_NOTICE', 'WITHDRAWAL_UPDATED')),
    channel      VARCHAR(10) NOT NULL CHECK (channel IN ('IN_APP', 'PUSH', 'SMS', 'EMAIL')),
    payload      JSONB       NOT NULL DEFAULT '{}'::jsonb,
    scheduled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status       VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'CANCELLED')),
    attempts     INTEGER    NOT NULL DEFAULT 0,
    sent_at      TIMESTAMPTZ,
    read_at      TIMESTAMPTZ,
    last_error   VARCHAR(500),
    dedupe_key   VARCHAR(150) UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (user_id IS NOT NULL OR phone IS NOT NULL)
);
CREATE INDEX ix_notifications_due ON notifications (scheduled_at) WHERE status = 'PENDING';
CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC);

-- =====================================================================================
-- 12. NHẬT KÝ KHÔNG SỬA ĐƯỢC (§2) VÀ IDEMPOTENCY
-- =====================================================================================

-- Mọi thao tác nhạy cảm: ra lệnh máy, hoàn tiền ngoại lệ, kích hoạt thủ công, đổi giá/cấu hình,
-- duyệt rút ví, đổi trạng thái máy, duyệt/thu hồi thiết bị.
CREATE TABLE audit_logs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_type    VARCHAR(10) NOT NULL CHECK (actor_type IN ('USER', 'KIOSK', 'DEVICE', 'SYSTEM')),
    actor_user_id UUID REFERENCES users (id),
    actor_ref     VARCHAR(64),                  -- kiosk id / device id
    action        VARCHAR(60) NOT NULL,
    entity_type   VARCHAR(40) NOT NULL,
    entity_id     VARCHAR(64),
    reason        VARCHAR(500),
    before_data   JSONB,
    after_data    JSONB,
    ip_address    VARCHAR(45),
    user_agent    VARCHAR(255),
    CHECK (actor_type <> 'USER' OR (actor_user_id IS NOT NULL AND reason IS NOT NULL))
);
CREATE INDEX ix_audit_logs_entity ON audit_logs (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_logs_actor ON audit_logs (actor_user_id, occurred_at DESC);

-- Chặn UPDATE/DELETE trên các bảng chỉ-thêm.
CREATE FUNCTION forbid_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_wallet_transactions_append_only
    BEFORE UPDATE OR DELETE ON wallet_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_order_events_append_only
    BEFORE UPDATE ON order_events
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_audit_logs_no_truncate
    BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_wallet_transactions_no_truncate
    BEFORE TRUNCATE ON wallet_transactions
    FOR EACH STATEMENT EXECUTE FUNCTION forbid_mutation();

-- Chặn tạo trùng khi client gửi lại cùng một request (tạo đơn, tạo phiếu thu, rút ví...).
CREATE TABLE idempotency_keys (
    scope           VARCHAR(80)  NOT NULL,     -- "user:<id>" hoặc "kiosk:<id>"
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INTEGER,
    response_body   JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (scope, idempotency_key)
);
CREATE INDEX ix_idempotency_keys_expiry ON idempotency_keys (expires_at);

-- =====================================================================================
-- 13. RÀNG BUỘC TIỀN HOÀN (§15.1): tổng hoàn của một đơn không bao giờ vượt số khách đã trả.
-- =====================================================================================

CREATE FUNCTION check_refund_cap() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_payment_status VARCHAR(10);
    v_paid           BIGINT;
    v_refunded       BIGINT;
BEGIN
    IF NEW.status IN ('REJECTED', 'FAILED') THEN
        RETURN NEW;
    END IF;

    IF NEW.payment_id IS NOT NULL THEN
        SELECT status, amount INTO v_payment_status, v_paid FROM payments WHERE id = NEW.payment_id FOR UPDATE;
    END IF;

    IF v_payment_status = 'REJECTED' THEN
        -- Khoản tiền không được ghi nhận cho đơn: hoàn tối đa đúng khoản đó.
        SELECT COALESCE(SUM(amount), 0) INTO v_refunded
        FROM refunds
        WHERE payment_id = NEW.payment_id AND status NOT IN ('REJECTED', 'FAILED') AND id <> NEW.id;
    ELSE
        IF NEW.order_id IS NULL THEN
            RAISE EXCEPTION 'refund % must reference an order', NEW.id USING ERRCODE = '23514';
        END IF;
        PERFORM 1 FROM orders WHERE id = NEW.order_id FOR UPDATE;  -- tuần tự hóa các lệnh hoàn của đơn
        SELECT COALESCE(SUM(amount), 0) INTO v_paid
        FROM payments
        WHERE order_id = NEW.order_id AND status = 'CAPTURED';
        SELECT COALESCE(SUM(r.amount), 0) INTO v_refunded
        FROM refunds r
        LEFT JOIN payments p ON p.id = r.payment_id
        WHERE r.order_id = NEW.order_id
          AND r.status NOT IN ('REJECTED', 'FAILED')
          AND r.id <> NEW.id
          AND (p.id IS NULL OR p.status = 'CAPTURED');
    END IF;

    IF v_refunded + NEW.amount > v_paid THEN
        RAISE EXCEPTION 'refund exceeds amount paid (paid %, already refunded %, requested %)',
            v_paid, v_refunded, NEW.amount USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_refunds_cap
    BEFORE INSERT OR UPDATE OF amount, status, order_id, payment_id ON refunds
    FOR EACH ROW EXECUTE FUNCTION check_refund_cap();

-- =====================================================================================
-- 14. DỮ LIỆU KHỞI TẠO
-- =====================================================================================

INSERT INTO store_settings (id, name) VALUES (1, 'LaundryOS');

-- =====================================================================================
-- 15. QUYỀN CHO EMQX
-- Role "emqx_auth" do script docker/postgres/init tạo (hoặc DBA tạo tay trên DB ngoài).
-- Nếu role chưa có thì bỏ qua; tạo role xong chạy lại 2 lệnh GRANT bên dưới.
-- =====================================================================================

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'emqx_auth') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA ' || quote_ident(current_schema()) || ' TO emqx_auth';
        GRANT SELECT ON mqtt_accounts TO emqx_auth;
    END IF;
END;
$$;
