CREATE TABLE booking_service_definitions (
    code VARCHAR(40) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    service_mode VARCHAR(30) NOT NULL CHECK (service_mode IN ('SELF_SERVICE', 'ASSISTED_LOCKER')),
    description VARCHAR(500) NOT NULL,
    processing_minutes INTEGER NOT NULL CHECK (processing_minutes > 0),
    secondary_processing_minutes INTEGER NOT NULL DEFAULT 0 CHECK (secondary_processing_minutes >= 0),
    check_in_minutes INTEGER NOT NULL DEFAULT 5 CHECK (check_in_minutes >= 0),
    checkout_minutes INTEGER NOT NULL DEFAULT 5 CHECK (checkout_minutes >= 0),
    transfer_minutes INTEGER NOT NULL DEFAULT 0 CHECK (transfer_minutes >= 0),
    pickup_deadline_hours INTEGER NOT NULL DEFAULT 0 CHECK (pickup_deadline_hours >= 0),
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE booking_service_requirements (
    service_code VARCHAR(40) NOT NULL REFERENCES booking_service_definitions(code),
    resource_type VARCHAR(30) NOT NULL CHECK (resource_type IN ('WASHER', 'DRYER', 'IRON_STATION', 'LOCKER')),
    sequence_no SMALLINT NOT NULL,
    PRIMARY KEY (service_code, resource_type),
    UNIQUE (service_code, sequence_no)
);

CREATE TABLE branch_booking_settings (
    branch_id UUID PRIMARY KEY REFERENCES branches(id) ON DELETE CASCADE,
    timezone VARCHAR(80) NOT NULL
);

CREATE TABLE branch_booking_hours (
    branch_id UUID NOT NULL REFERENCES branch_booking_settings(branch_id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    opens_at TIME NOT NULL,
    closes_at TIME NOT NULL,
    PRIMARY KEY (branch_id, day_of_week),
    CHECK (closes_at <> opens_at)
);

CREATE TABLE branch_service_offerings (
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    service_code VARCHAR(40) NOT NULL REFERENCES booking_service_definitions(code),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    price NUMERIC(10, 2) NOT NULL CHECK (price > 0),
    processing_minutes_override INTEGER CHECK (processing_minutes_override > 0),
    secondary_processing_minutes_override INTEGER CHECK (secondary_processing_minutes_override > 0),
    PRIMARY KEY (branch_id, service_code)
);

CREATE TABLE branch_service_resources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    branch_id UUID NOT NULL REFERENCES branches(id) ON DELETE CASCADE,
    resource_type VARCHAR(30) NOT NULL CHECK (resource_type IN ('IRON_STATION', 'LOCKER')),
    resource_code VARCHAR(50) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (branch_id, resource_code)
);

CREATE TABLE scheduled_bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id UUID NOT NULL UNIQUE REFERENCES transactions(id) ON DELETE CASCADE,
    branch_id UUID NOT NULL REFERENCES branches(id),
    service_code VARCHAR(40) NOT NULL REFERENCES booking_service_definitions(code),
    status VARCHAR(40) NOT NULL CHECK (status IN (
        'HELD', 'CONFIRMED', 'CHECKED_IN', 'IN_PROGRESS', 'TRANSFER_PENDING',
        'COMPLETED', 'CANCELLED', 'EXPIRED', 'PAYMENT_REVIEW_REQUIRED'
    )),
    start_time TIMESTAMPTZ NOT NULL,
    expected_end_time TIMESTAMPTZ NOT NULL,
    hold_expires_at TIMESTAMPTZ NOT NULL,
    timeline JSONB NOT NULL,
    idempotency_key VARCHAR(120) UNIQUE,
    reconciliation_reason VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (expected_end_time > start_time)
);

CREATE INDEX idx_scheduled_bookings_branch_start
    ON scheduled_bookings(branch_id, start_time);
CREATE INDEX idx_scheduled_bookings_hold_expiry
    ON scheduled_bookings(hold_expires_at)
    WHERE status = 'HELD';

CREATE TABLE booking_resource_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL REFERENCES scheduled_bookings(id) ON DELETE CASCADE,
    resource_id UUID NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    phase VARCHAR(30) NOT NULL,
    occupied_from TIMESTAMPTZ NOT NULL,
    occupied_until TIMESTAMPTZ NOT NULL,
    CHECK (occupied_until > occupied_from)
);

CREATE INDEX idx_booking_resource_allocations_overlap
    ON booking_resource_allocations(resource_id, occupied_from, occupied_until);
CREATE INDEX idx_booking_resource_allocations_booking
    ON booking_resource_allocations(booking_id);

INSERT INTO booking_service_definitions
    (code, name, service_mode, description, processing_minutes, secondary_processing_minutes, check_in_minutes, checkout_minutes, transfer_minutes, pickup_deadline_hours)
VALUES
    ('WASH_ONLY', 'Giặt', 'SELF_SERVICE', 'Tự giặt bằng máy giặt tại cửa hàng.', 35, 0, 5, 5, 0, 0),
    ('DRY_ONLY', 'Sấy', 'SELF_SERVICE', 'Tự sấy quần áo tại cửa hàng.', 45, 0, 5, 5, 0, 0),
    ('WASH_DRY', 'Giặt - sấy', 'SELF_SERVICE', 'Đặt đồng thời máy giặt và máy sấy.', 35, 45, 5, 5, 10, 0),
    ('IRON_ONLY', 'Ủi', 'SELF_SERVICE', 'Sử dụng bàn ủi tại cửa hàng.', 30, 0, 5, 5, 0, 0),
    ('ASSISTED_LOCKER', 'Locker · Giặt hộ', 'ASSISTED_LOCKER', 'Gửi đồ tại locker để nhân viên xử lý.', 1440, 0, 5, 5, 0, 48);

INSERT INTO booking_service_requirements (service_code, resource_type, sequence_no) VALUES
    ('WASH_ONLY', 'WASHER', 1),
    ('DRY_ONLY', 'DRYER', 1),
    ('WASH_DRY', 'WASHER', 1),
    ('WASH_DRY', 'DRYER', 2),
    ('IRON_ONLY', 'IRON_STATION', 1),
    ('ASSISTED_LOCKER', 'LOCKER', 1);
