package com.laundry.device;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Ghi tin nhắn bo gửi lên vào device_messages (bỏ telemetry/heartbeat để bảng không phình). */
@Component
@RequiredArgsConstructor
public class DeviceMessageLog {

    private final JdbcTemplate jdbc;

    public void record(String deviceId, String channel, String messageType, String json) {
        jdbc.update("insert into device_messages (device_id, channel, message_type, payload) values (?, ?, ?, cast(? as jsonb))",
                deviceId, channel, messageType, json);
    }

    /** Dọn nhật ký cũ hơn {@code days} ngày. */
    public int purgeOlderThanDays(int days) {
        return jdbc.update("delete from device_messages where received_at < now() - make_interval(days => ?)", days);
    }
}
