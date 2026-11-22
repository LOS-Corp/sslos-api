-- V66: bo retrofit nạp lượt qua cổng nhận xu theo profile giá, và khách mua thêm phút sấy khi máy đang sấy.
--
-- * price_profiles.vend_pulses: số xu (xung) bo retrofit nạp vào máy hãng cho một lượt, khớp giá đặt trên máy
--   (ví dụ máy đòi 6 xu cho 30.000đ). null = dùng VEND_PULSES trong firmware. Bo LaundryOS bỏ qua.
-- * price_profiles.extra_*: máy sấy bán thêm thời gian (ví dụ +12 phút 15.000đ). extra_vend_pulses = số xu bo
--   retrofit nạp thêm cho máy sấy hãng (máy sấy xu cộng phút theo mỗi xu); null = 1 xu.
-- * dry_extensions: mỗi lần mua thêm là một giao dịch riêng (ví trước, rồi payOS), sau khi trả xong backend gửi
--   EXTEND_CYCLE xuống bo; bo từ chối / không trả lời thì tiền về ví.

ALTER TABLE price_profiles
    ADD COLUMN vend_pulses       INTEGER,
    ADD COLUMN extra_minutes     INTEGER,
    ADD COLUMN extra_price       NUMERIC(10, 2),
    ADD COLUMN extra_vend_pulses INTEGER,
    ADD CONSTRAINT chk_price_profiles_vend_pulses CHECK (vend_pulses IS NULL OR vend_pulses BETWEEN 1 AND 50),
    ADD CONSTRAINT chk_price_profiles_extra CHECK (
        (extra_minutes IS NULL AND extra_price IS NULL AND extra_vend_pulses IS NULL)
        OR (extra_minutes BETWEEN 1 AND 120 AND extra_price > 0
            AND (extra_vend_pulses IS NULL OR extra_vend_pulses BETWEEN 1 AND 50))),
    ADD CONSTRAINT chk_price_profiles_extra_dryer CHECK (extra_minutes IS NULL OR machine_type = 'DRYER');

CREATE TABLE dry_extensions (
    transaction_id UUID PRIMARY KEY REFERENCES transactions (id) ON DELETE CASCADE,
    booking_id     UUID          NOT NULL REFERENCES scheduled_bookings (id) ON DELETE CASCADE,
    allocation_id  UUID          NOT NULL REFERENCES booking_resource_allocations (id) ON DELETE CASCADE,
    machine_id     UUID          NOT NULL REFERENCES machines (id),
    minutes        INTEGER       NOT NULL CHECK (minutes > 0),
    price          NUMERIC(10, 2) NOT NULL CHECK (price > 0),
    vend_pulses    INTEGER,
    -- PENDING chờ trả tiền; PAID đã trả, lệnh đang gửi xuống bo; APPLIED bo đã nhận;
    -- CANCELLED thanh toán không thành công; REFUNDED đã trả nhưng không áp dụng được, tiền về ví.
    status         VARCHAR(20)   NOT NULL DEFAULT 'PENDING'
                   CHECK (status IN ('PENDING', 'PAID', 'APPLIED', 'CANCELLED', 'REFUNDED')),
    command_id     VARCHAR(80),
    fail_reason    VARCHAR(200),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    paid_at        TIMESTAMPTZ,
    applied_at     TIMESTAMPTZ
);
CREATE INDEX idx_dry_extensions_booking ON dry_extensions (booking_id);
CREATE UNIQUE INDEX uq_dry_extensions_command ON dry_extensions (command_id) WHERE command_id IS NOT NULL;
-- Một lượt mua thêm chưa trả cho mỗi lượt sấy.
CREATE UNIQUE INDEX uq_dry_extensions_pending ON dry_extensions (allocation_id) WHERE status = 'PENDING';
