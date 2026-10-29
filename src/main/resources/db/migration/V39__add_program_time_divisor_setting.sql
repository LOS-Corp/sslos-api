-- Platform-wide settings. program.time_divisor is sent with every program in
-- AUTHORIZE_START: 1 = programs run their real minutes, N > 1 = demo, N times faster.
CREATE TABLE IF NOT EXISTS app_settings (
    setting_key   VARCHAR(80) PRIMARY KEY,
    setting_value VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO app_settings (setting_key, setting_value)
VALUES ('program.time_divisor', '1')
ON CONFLICT (setting_key) DO NOTHING;
