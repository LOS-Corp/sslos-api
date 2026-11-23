package com.laundry.machine;

import com.laundry.machine.MachineDtos.MachineView;
import com.laundry.machine.MachineDtos.MaintenanceView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Số liệu và lịch sử của một máy cho màn Chi tiết máy (chỉ đọc). Một "lượt chạy" là một chặng đơn (order_legs) mà máy
 * đã thật sự chạy (started_at có giá trị, không bị hủy); doanh thu tính theo giá chặng lúc đặt. Ngày tính theo múi giờ
 * cửa hàng (store_settings.timezone).
 */
@Service
@RequiredArgsConstructor
public class MachineInsightService {

    public static final int MAX_DAYS = 90;

    private final MachineService machines;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public record DeviceInfo(String id, String linkState, boolean online, Instant lastSeenAt, String firmwareVersion,
                             Integer rssi, String ip, String fingerprint) {
    }

    /** runMinutes: tổng phút máy thật sự chạy; interrupted: lượt bị ngắt giữa chừng (máy hỏng, mất điện). */
    public record Kpi(int runs, long revenueVnd, int runMinutes, int interrupted) {
    }

    public record DayStat(LocalDate date, int runs, long revenueVnd) {
    }

    public record Upcoming(String orderCode, String service, int legNo, String legStatus, Instant startsAt,
                           Instant endsAt) {
    }

    public record Insight(MachineView machine, DeviceInfo device, String timezone, int periodDays, Kpi today,
                          Kpi period, List<DayStat> daily, List<Upcoming> upcoming,
                          List<MaintenanceView> maintenance) {
    }

    /**
     * Một dòng lịch sử. kind: RUN (lượt chạy), COMMAND (lệnh nhân viên gửi), ADMIN (đổi giá/trạng thái/bảo trì...),
     * DEVICE (gắn/gỡ bo). action: mã gốc (trạng thái chặng hoặc mã nhật ký) để giao diện dịch nhãn.
     */
    public record ActivityItem(Instant at, String kind, String action, String reason, String actor, String orderCode,
                               Long amountVnd, Integer minutes, Map<String, Object> data) {
    }

    private record Leg(Instant startedAt, Instant endedAt, long price, String status) {
    }

    @Transactional(readOnly = true)
    public Insight insight(UUID machineId, int requestedDays) {
        MachineView machine = machines.view(machineId);
        int days = Math.clamp(requestedDays, 1, MAX_DAYS);
        ZoneId zone = storeZone();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate first = today.minusDays(days - 1L);
        Instant periodStart = first.atStartOfDay(zone).toInstant();

        List<Leg> legs = jdbc.query("""
                        select started_at, coalesce(finished_at, done_at, expected_end_at) as ended_at, price_amount, status
                        from order_legs
                        where machine_id = :id and started_at is not null and status <> 'CANCELLED' and started_at >= :from
                        """,
                new MapSqlParameterSource("id", machineId).addValue("from", Timestamp.from(periodStart)),
                (rs, i) -> new Leg(rs.getTimestamp(1).toInstant(),
                        rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(), rs.getLong(3), rs.getString(4)));

        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) byDay.put(d, new long[2]);
        int[] periodTotals = new int[3];
        long periodRevenue = 0;
        int[] todayTotals = new int[3];
        long todayRevenue = 0;
        for (Leg leg : legs) {
            LocalDate day = LocalDate.ofInstant(leg.startedAt(), zone);
            int minutes = runMinutes(leg, now);
            boolean interrupted = "INTERRUPTED".equals(leg.status());
            long[] bucket = byDay.get(day);
            if (bucket != null) {
                bucket[0]++;
                bucket[1] += leg.price();
            }
            periodTotals[0]++;
            periodTotals[1] += minutes;
            if (interrupted) periodTotals[2]++;
            periodRevenue += leg.price();
            if (day.equals(today)) {
                todayTotals[0]++;
                todayTotals[1] += minutes;
                if (interrupted) todayTotals[2]++;
                todayRevenue += leg.price();
            }
        }
        List<DayStat> daily = byDay.entrySet().stream()
                .map(e -> new DayStat(e.getKey(), (int) e.getValue()[0], e.getValue()[1])).toList();

        List<Upcoming> upcoming = jdbc.query("""
                        select o.code, o.service, l.leg_no, l.status, r.starts_at, r.ends_at
                        from order_legs l
                        join orders o on o.id = l.order_id
                        join machine_reservations r on r.id = l.reservation_id
                        where l.machine_id = :id and r.active and r.ends_at > :now
                          and l.status in ('SCHEDULED', 'ACTIVATED', 'RUNNING', 'AWAITING_UNLOAD')
                        order by r.starts_at
                        limit 8
                        """,
                new MapSqlParameterSource("id", machineId).addValue("now", Timestamp.from(now)),
                (rs, i) -> new Upcoming(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant()));

        return new Insight(machine, device(machineId), zone.getId(), days,
                new Kpi(todayTotals[0], todayRevenue, todayTotals[1], todayTotals[2]),
                new Kpi(periodTotals[0], periodRevenue, periodTotals[1], periodTotals[2]),
                daily, upcoming, machines.maintenance(machineId));
    }

    /** Lịch sử mới nhất trước: lượt chạy, lệnh, thao tác quản lý và gắn/gỡ bo của máy. */
    @Transactional(readOnly = true)
    public List<ActivityItem> activity(UUID machineId, int requestedLimit) {
        machines.get(machineId);
        int limit = Math.clamp(requestedLimit, 1, 200);
        MapSqlParameterSource p = new MapSqlParameterSource("id", machineId)
                .addValue("idText", machineId.toString()).addValue("limit", limit);
        List<ActivityItem> runs = jdbc.query("""
                        select l.started_at, l.status, o.code, l.price_amount, l.cycle_minutes + l.extra_minutes,
                               l.interruption_reason, l.stopped_by_customer
                        from order_legs l join orders o on o.id = l.order_id
                        where l.machine_id = :id and l.started_at is not null
                        order by l.started_at desc
                        limit :limit
                        """, p,
                (rs, i) -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    if (rs.getBoolean(7)) data.put("stoppedByCustomer", true);
                    return new ActivityItem(rs.getTimestamp(1).toInstant(), "RUN", rs.getString(2), rs.getString(6),
                            null, rs.getString(3), rs.getLong(4), rs.getInt(5), data);
                });
        List<ActivityItem> logs = jdbc.query("""
                        select a.occurred_at, a.action, a.reason, coalesce(u.full_name, a.actor_type) as actor,
                               a.entity_type, a.after_data::text, a.before_data::text
                        from audit_logs a left join users u on u.id = a.actor_user_id
                        where (a.entity_type = 'machine' and a.entity_id = :idText)
                           or (a.entity_type = 'device' and a.action in ('DEVICE_BOUND', 'DEVICE_UNBOUND')
                               and (a.after_data ->> 'machineId' = :idText or a.before_data ->> 'machineId' = :idText))
                        order by a.occurred_at desc
                        limit :limit
                        """, p,
                (rs, i) -> {
                    String action = rs.getString(2);
                    String kind = "device".equals(rs.getString(5)) ? "DEVICE"
                            : "MACHINE_COMMAND".equals(action) ? "COMMAND" : "ADMIN";
                    Map<String, Object> data = new LinkedHashMap<>();
                    if (rs.getString(6) != null) data.put("after", rs.getString(6));
                    if (rs.getString(7) != null) data.put("before", rs.getString(7));
                    return new ActivityItem(rs.getTimestamp(1).toInstant(), kind, action, rs.getString(3),
                            rs.getString(4), null, null, null, data);
                });
        List<ActivityItem> all = new ArrayList<>(runs.size() + logs.size());
        all.addAll(runs);
        all.addAll(logs);
        all.sort((a, b) -> b.at().compareTo(a.at()));
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    private DeviceInfo device(UUID machineId) {
        List<DeviceInfo> found = jdbc.query("""
                        select id, link_state, online, last_seen_at, firmware_version, last_rssi, last_ip, key_fingerprint
                        from devices where machine_id = :id
                        """, new MapSqlParameterSource("id", machineId),
                (rs, i) -> new DeviceInfo(rs.getString(1), rs.getString(2), rs.getBoolean(3),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant(), rs.getString(5),
                        (Integer) rs.getObject(6), rs.getString(7), rs.getString(8)));
        return found.isEmpty() ? null : found.getFirst();
    }

    private ZoneId storeZone() {
        List<String> tz = jdbc.queryForList("select timezone from store_settings where id = 1", Map.of(), String.class);
        try {
            return tz.isEmpty() ? ZoneId.of("Asia/Ho_Chi_Minh") : ZoneId.of(tz.getFirst());
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Ho_Chi_Minh");
        }
    }

    /** Phút máy đã chạy: tới lúc kết thúc, hoặc tới bây giờ nếu đang chạy. */
    private static int runMinutes(Leg leg, Instant now) {
        Instant end = leg.endedAt() == null || leg.endedAt().isAfter(now) ? now : leg.endedAt();
        return (int) Math.max(0, Duration.between(leg.startedAt(), end).toMinutes());
    }
}
