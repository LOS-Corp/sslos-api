-- =============================================================================
-- V4: Trạng thái lắp đặt (triển khai) của máy và kết quả self-test gần nhất.
-- UNASSIGNED (chưa gắn bo) -> PROVISIONED (đã gắn, chờ self-test) -> TESTING -> TEST_PASSED / TEST_FAILED
-- -> ACTIVE (đưa vào hoạt động, khách đặt được). ACTIVE -> MAINTENANCE (tạm ngừng để sửa) -> self-test -> ACTIVE.
-- Chỉ máy ACTIVE mới bán được. Máy đang gắn bo lúc nâng cấp coi như đã đưa vào hoạt động để không ngừng phục vụ.
-- =============================================================================

ALTER TABLE machines
    ADD COLUMN deployment_status       VARCHAR(12) NOT NULL DEFAULT 'UNASSIGNED'
        CHECK (deployment_status IN ('UNASSIGNED', 'PROVISIONED', 'TESTING', 'TEST_FAILED', 'TEST_PASSED',
                                     'ACTIVE', 'MAINTENANCE')),
    ADD COLUMN self_test_command_id    UUID,          -- lệnh SELF_TEST đang chờ / gần nhất (khớp commandId bo trả về)
    ADD COLUMN self_test_requested_at  TIMESTAMPTZ,
    ADD COLUMN last_self_test_at       TIMESTAMPTZ,
    ADD COLUMN last_self_test_passed   BOOLEAN,
    ADD COLUMN last_self_test_detail   JSONB,         -- các hạng mục "checks" do firmware báo
    ADD COLUMN maintenance_since       TIMESTAMPTZ,   -- giữ trong lúc self-test sau sửa chữa, xóa khi hoàn tất
    ADD COLUMN commissioned_at         TIMESTAMPTZ;

UPDATE machines m
SET deployment_status = 'ACTIVE',
    commissioned_at   = now()
WHERE EXISTS (SELECT 1 FROM devices d WHERE d.machine_id = m.id AND d.link_state = 'BOUND');

CREATE INDEX ix_machines_testing ON machines (self_test_requested_at) WHERE deployment_status = 'TESTING';
