package com.laundry.machine;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trạng thái hiển thị của máy (§16): Sẵn sàng · Đã giữ cho đơn · Chờ bấm START · Đang chạy · Xong, chờ lấy đồ ·
 * Lỗi · Bảo trì · Mất kết nối. Suy ra từ admin_status + thiết bị + chặng đang mở + lịch giữ máy, không lưu trùng.
 */
@Component
@RequiredArgsConstructor
public class MachineStatusResolver {

    public enum DisplayStatus { READY, RESERVED, AWAITING_START, RUNNING, AWAITING_UNLOAD, ERROR, MAINTENANCE, OFFLINE, RETIRED }

    public record Live(DisplayStatus status, boolean online, String deviceId, Integer remainingSeconds,
                       Instant busyUntil) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public Map<UUID, Live> resolve(Collection<Machine> machines) {
        Map<UUID, Live> out = new HashMap<>();
        if (machines.isEmpty()) return out;
        List<UUID> ids = machines.stream().map(Machine::getId).toList();
        Instant now = clock.instant();
        MapSqlParameterSource p = new MapSqlParameterSource("ids", ids).addValue("now", Timestamp.from(now));

        Map<UUID, Object[]> devices = new HashMap<>();
        jdbc.query("""
                select machine_id, id, online, (last_status->>'remainingSeconds')::int
                from devices where machine_id in (:ids) and link_state = 'BOUND'
                """, p, rs -> {
            devices.put(rs.getObject(1, UUID.class), new Object[]{rs.getString(2), rs.getBoolean(3),
                    (Integer) rs.getObject(4)});
        });

        Map<UUID, String> legs = new HashMap<>();
        jdbc.query("""
                select machine_id, status from order_legs
                where machine_id in (:ids) and status in ('ACTIVATED', 'RUNNING', 'AWAITING_UNLOAD')
                """, p, rs -> {
            UUID id = rs.getObject(1, UUID.class);
            String status = rs.getString(2);
            legs.merge(id, status, (a, b) -> rank(a) >= rank(b) ? a : b);
        });

        Map<UUID, Object[]> reservations = new HashMap<>();
        jdbc.query("""
                select machine_id, kind, ends_at from machine_reservations
                where machine_id in (:ids) and active and starts_at <= :now and ends_at > :now
                """, p, rs -> {
            reservations.put(rs.getObject(1, UUID.class), new Object[]{rs.getString(2),
                    rs.getTimestamp(3).toInstant()});
        });

        for (Machine m : machines) {
            Object[] d = devices.get(m.getId());
            boolean online = d != null && (Boolean) d[1];
            String deviceId = d == null ? null : (String) d[0];
            Integer remaining = d == null ? null : (Integer) d[2];
            Object[] r = reservations.get(m.getId());
            Instant busyUntil = r == null ? null : (Instant) r[1];
            DisplayStatus status;
            if (m.getAdminStatus() == Machine.AdminStatus.RETIRED) status = DisplayStatus.RETIRED;
            else if (m.getAdminStatus() == Machine.AdminStatus.ERROR) status = DisplayStatus.ERROR;
            else if (m.getAdminStatus() == Machine.AdminStatus.MAINTENANCE
                    || (r != null && "MAINTENANCE".equals(r[0]))) status = DisplayStatus.MAINTENANCE;
            else if (!online) status = DisplayStatus.OFFLINE;
            else {
                String leg = legs.get(m.getId());
                if ("RUNNING".equals(leg)) status = DisplayStatus.RUNNING;
                else if ("AWAITING_UNLOAD".equals(leg)) status = DisplayStatus.AWAITING_UNLOAD;
                else if ("ACTIVATED".equals(leg)) status = DisplayStatus.AWAITING_START;
                else if (r != null) status = DisplayStatus.RESERVED;
                else status = DisplayStatus.READY;
            }
            out.put(m.getId(), new Live(status, online, deviceId,
                    status == DisplayStatus.RUNNING ? remaining : null, busyUntil));
        }
        return out;
    }

    public Live resolve(Machine m) {
        return resolve(List.of(m)).get(m.getId());
    }

    private static int rank(String status) {
        return switch (status) {
            case "RUNNING" -> 3;
            case "ACTIVATED" -> 2;
            default -> 1;
        };
    }
}
