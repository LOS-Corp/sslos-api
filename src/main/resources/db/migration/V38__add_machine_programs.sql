-- Selectable wash / dry programs.
-- A program fixes how long the machine runs (and so how long a booking holds
-- it), a surcharge on top of the branch service price, and the parameters the
-- firmware receives in AUTHORIZE_START. Bookings keep a snapshot of the chosen
-- programs, so editing a program never changes a booking that was already paid.

CREATE TABLE machine_programs (
    code               VARCHAR(40)   PRIMARY KEY,
    machine_type       VARCHAR(20)   NOT NULL CHECK (machine_type IN ('WASHER', 'DRYER')),
    name               VARCHAR(80)   NOT NULL,
    description        VARCHAR(300)  NOT NULL DEFAULT '',
    surcharge          NUMERIC(10, 2) NOT NULL DEFAULT 0 CHECK (surcharge >= 0),
    -- Washer: wash / spin. Dryer: dry / cool.
    main_minutes       INTEGER       NOT NULL CHECK (main_minutes BETWEEN 1 AND 180),
    finish_minutes     INTEGER       NOT NULL CHECK (finish_minutes BETWEEN 0 AND 30),
    -- Washer only: drum turns this long, then rests before reversing.
    drum_run_seconds   INTEGER       CHECK (drum_run_seconds BETWEEN 5 AND 60),
    drum_pause_seconds INTEGER       CHECK (drum_pause_seconds BETWEEN 3 AND 30),
    -- Dryer only: heater duty cycle and UV lamp during the drying phase.
    heat_percent       INTEGER       CHECK (heat_percent BETWEEN 0 AND 100),
    uv_enabled         BOOLEAN,
    is_default         BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order         SMALLINT      NOT NULL DEFAULT 0,
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_machine_program_params CHECK (
        (machine_type = 'WASHER' AND drum_run_seconds IS NOT NULL AND drum_pause_seconds IS NOT NULL
            AND heat_percent IS NULL AND uv_enabled IS NULL)
        OR (machine_type = 'DRYER' AND heat_percent IS NOT NULL AND uv_enabled IS NOT NULL
            AND finish_minutes >= 1 AND drum_run_seconds IS NULL AND drum_pause_seconds IS NULL)),
    CONSTRAINT chk_machine_program_default_active CHECK (NOT is_default OR active)
);

-- Exactly one default per machine type is enforced in the service; at most one here.
CREATE UNIQUE INDEX uq_machine_programs_default ON machine_programs (machine_type) WHERE is_default;

-- The defaults match the former fixed service durations (35 min wash, 45 min dry).
INSERT INTO machine_programs
    (code, machine_type, name, description, surcharge, main_minutes, finish_minutes,
     drum_run_seconds, drum_pause_seconds, heat_percent, uv_enabled, is_default, sort_order)
VALUES
    ('WASH_QUICK', 'WASHER', 'Giặt nhanh', 'Đồ ít bẩn, mặc hằng ngày.', 0, 20, 5, 10, 3, NULL, NULL, FALSE, 1),
    ('WASH_NORMAL', 'WASHER', 'Giặt thường', 'Phù hợp hầu hết quần áo.', 0, 30, 5, 12, 3, NULL, NULL, TRUE, 2),
    ('WASH_DELICATE', 'WASHER', 'Đồ mỏng nhẹ', 'Lồng quay nhẹ, nghỉ lâu hơn để giữ form vải.', 0, 25, 3, 6, 6, NULL, NULL, FALSE, 3),
    ('WASH_HEAVY', 'WASHER', 'Đồ dày / chăn mền', 'Giặt lâu, vắt kỹ cho jean, khăn, chăn mỏng.', 10000, 45, 8, 15, 4, NULL, NULL, FALSE, 4),
    ('DRY_LOW', 'DRYER', 'Sấy nhiệt thấp', 'Nhiệt nhẹ cho đồ thun, đồ dễ co.', 0, 40, 5, NULL, NULL, 50, TRUE, FALSE, 1),
    ('DRY_NORMAL', 'DRYER', 'Sấy thường', 'Nhiệt tiêu chuẩn kèm đèn UV.', 0, 40, 5, NULL, NULL, 100, TRUE, TRUE, 2),
    ('DRY_HEAVY', 'DRYER', 'Sấy lâu (đồ dày)', 'Sấy lâu hơn cho jean, khăn, áo khoác.', 10000, 60, 8, NULL, NULL, 100, TRUE, FALSE, 3),
    ('DRY_REFRESH', 'DRYER', 'Làm thoáng & UV', 'Chỉ quạt và đèn UV, không bật nhiệt.', 0, 15, 1, NULL, NULL, 0, TRUE, FALSE, 4);

ALTER TABLE scheduled_bookings
    ADD COLUMN programs JSONB NOT NULL DEFAULT '[]'::jsonb;
