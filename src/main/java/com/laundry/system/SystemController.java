package com.laundry.system;

import com.laundry.auth.LoginEventService;
import com.laundry.common.PageResponse;
import com.laundry.device.link.MqttProperties;
import com.laundry.device.link.MqttTransport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
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

/** Quản trị viên: giám sát hệ thống (backend, database, EMQX, bo mạch), thống kê toàn hệ thống, lịch sử đăng nhập. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Admin · Hệ thống")
public class SystemController {

    private final JdbcTemplate jdbc;
    private final MqttProperties mqtt;
    private final ObjectProvider<MqttTransport> transport;
    private final LoginEventService loginEvents;
    private final Clock clock;

    @Value("${spring.application.name:sslos-api}")
    private String appName;

    /** UP: bình thường; DEGRADED: chạy được nhưng có phần đáng lo; DOWN: không dùng được. */
    public enum Level { UP, DEGRADED, DOWN }

    public record Component(String key, String name, Level level, String message, Map<String, Object> details) {
    }

    public record Health(Level overall, Instant checkedAt, List<Component> components) {
    }

    @GetMapping("/api/admin/system/health")
    @Operation(summary = "Tình trạng backend, database, EMQX (phiên backend + cổng TLS cho bo) và kết nối bo mạch")
    public Health health() {
        List<Component> components = new ArrayList<>();
        components.add(backend());
        components.add(database());
        components.add(broker());
        components.add(devices());
        Level overall = components.stream().map(Component::level)
                .reduce(Level.UP, (a, b) -> a.ordinal() >= b.ordinal() ? a : b);
        return new Health(overall, clock.instant(), components);
    }

    private Component backend() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("application", appName);
        d.put("startedAt", Instant.ofEpochMilli(runtime.getStartTime()).toString());
        d.put("uptimeSeconds", runtime.getUptime() / 1000);
        d.put("javaVersion", System.getProperty("java.version"));
        d.put("heapUsedMb", memory.getUsed() / 1_048_576);
        d.put("heapMaxMb", memory.getMax() < 0 ? null : memory.getMax() / 1_048_576);
        d.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        d.put("processors", Runtime.getRuntime().availableProcessors());
        double heapRatio = memory.getMax() > 0 ? memory.getUsed() / (double) memory.getMax() : 0;
        Level level = heapRatio > 0.9 ? Level.DEGRADED : Level.UP;
        return new Component("backend", "Backend (Spring Boot)", level,
                level == Level.UP ? "Đang chạy" : "Bộ nhớ heap gần đầy (" + Math.round(heapRatio * 100) + "%)", d);
    }

    private Component database() {
        Map<String, Object> d = new LinkedHashMap<>();
        try {
            long start = System.nanoTime();
            jdbc.queryForObject("select 1", Integer.class);
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            d.put("latencyMs", latencyMs);
            d.put("version", jdbc.queryForObject("show server_version", String.class));
            d.put("sizeMb", jdbc.queryForObject("select pg_database_size(current_database()) / 1048576", Long.class));
            d.put("connections", jdbc.queryForObject(
                    "select count(*) from pg_stat_activity where datname = current_database()", Long.class));
            d.put("schemaVersion", jdbc.queryForObject(
                    "select version from flyway_schema_history where success order by installed_rank desc limit 1",
                    String.class));
            Level level = latencyMs > 500 ? Level.DEGRADED : Level.UP;
            return new Component("database", "Database (PostgreSQL)", level,
                    level == Level.UP ? "Kết nối tốt" : "Phản hồi chậm (" + latencyMs + " ms)", d);
        } catch (RuntimeException e) {
            d.put("error", e.getClass().getSimpleName());
            return new Component("database", "Database (PostgreSQL)", Level.DOWN, "Không truy vấn được database", d);
        }
    }

    private Component broker() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("enabled", mqtt.enabled());
        d.put("brokerUrl", mqtt.brokerUrl());
        d.put("deviceTlsPort", mqtt.deviceTlsPort());
        if (!mqtt.enabled()) {
            return new Component("broker", "EMQX (MQTT)", Level.DEGRADED, "Backend đang tắt MQTT (app.mqtt.enabled=false)", d);
        }
        MqttTransport t = transport.getIfAvailable();
        boolean session = t != null && t.isBrokerConnected();
        boolean tls = reachable(mqtt.host(), mqtt.deviceTlsPort());
        d.put("backendSession", session);
        d.put("deviceTlsListener", tls);
        if (session && tls) return new Component("broker", "EMQX (MQTT)", Level.UP, "Backend đã nối broker; cổng TLS cho bo đang mở", d);
        if (!session) return new Component("broker", "EMQX (MQTT)", Level.DOWN, "Backend chưa nối được broker: bo không nhận lệnh", d);
        return new Component("broker", "EMQX (MQTT)", Level.DEGRADED,
                "Không mở được cổng TLS " + mqtt.deviceTlsPort() + ": bo mới không kết nối được", d);
    }

    private Component devices() {
        Map<String, Object> d = new LinkedHashMap<>();
        try {
            jdbc.query("""
                    select count(*) filter (where link_state = 'BOUND'),
                           count(*) filter (where link_state = 'BOUND' and online),
                           count(*) filter (where link_state = 'PENDING'),
                           count(*) filter (where link_state = 'VERIFIED'),
                           max(last_seen_at)
                    from devices
                    """, rs -> {
                d.put("bound", rs.getLong(1));
                d.put("online", rs.getLong(2));
                d.put("pendingApproval", rs.getLong(3));
                d.put("approvedUnbound", rs.getLong(4));
                Timestamp last = rs.getTimestamp(5);
                d.put("lastSeenAt", last == null ? null : last.toInstant().toString());
            });
            List<String> offline = jdbc.queryForList("""
                    select coalesce(m.code, l.code, d.id) from devices d
                    left join machines m on m.id = d.machine_id left join lockers l on l.id = d.locker_id
                    where d.link_state = 'BOUND' and not d.online order by 1 limit 20
                    """, String.class);
            d.put("offline", offline);
            long bound = (long) d.get("bound");
            long online = (long) d.get("online");
            Level level = bound == 0 || online == bound ? Level.UP : online == 0 ? Level.DOWN : Level.DEGRADED;
            String message = bound == 0 ? "Chưa có bo nào được gắn"
                    : online == bound ? "Cả " + bound + " bo đang kết nối"
                    : online + "/" + bound + " bo đang kết nối";
            return new Component("devices", "Bo mạch ESP32", level, message, d);
        } catch (RuntimeException e) {
            return new Component("devices", "Bo mạch ESP32", Level.DOWN, "Không đọc được trạng thái bo", d);
        }
    }

    private static boolean reachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- Thống kê hệ thống ----

    public record DayPoint(LocalDate date, long orders, long revenueVnd, long newUsers) {
    }

    public record Stats(Instant generatedAt, Map<String, Long> users, Map<String, Long> orders,
                        Map<String, Long> money, Map<String, Long> service, List<DayPoint> daily) {
    }

    @GetMapping("/api/admin/system/stats")
    @Operation(summary = "Số người dùng, đơn, giao dịch, tình trạng dịch vụ và chuỗi 30 ngày")
    public Stats stats(@RequestParam(defaultValue = "30") int days) {
        int span = Math.max(7, Math.min(days, 90));
        Instant now = clock.instant();
        Timestamp since = Timestamp.from(now.minus(Duration.ofDays(span)));
        ZoneId zone = ZoneId.of(jdbc.queryForObject("select timezone from store_settings where id = 1", String.class));

        Map<String, Long> users = jdbc.queryForObject("""
                select count(*),
                       count(*) filter (where exists (select 1 from user_roles r where r.user_id = u.id and r.role = 'CUSTOMER')),
                       count(*) filter (where exists (select 1 from user_roles r where r.user_id = u.id and r.role = 'STAFF')),
                       count(*) filter (where exists (select 1 from user_roles r where r.user_id = u.id and r.role = 'OWNER')),
                       count(*) filter (where exists (select 1 from user_roles r where r.user_id = u.id and r.role = 'ADMIN')),
                       count(*) filter (where status <> 'ACTIVE'),
                       count(*) filter (where created_at >= ?),
                       count(*) filter (where last_login_at >= ?),
                       count(*) filter (where booking_banned_until > now())
                from users u
                """, (rs, i) -> map("total", rs.getLong(1), "customers", rs.getLong(2), "staff", rs.getLong(3),
                "owners", rs.getLong(4), "admins", rs.getLong(5), "locked", rs.getLong(6), "newInPeriod", rs.getLong(7),
                "activeInPeriod", rs.getLong(8), "bookingBanned", rs.getLong(9)), since, since);

        Map<String, Long> orders = jdbc.queryForObject("""
                select count(*),
                       count(*) filter (where created_at >= ?),
                       count(*) filter (where status = 'COMPLETED'),
                       count(*) filter (where status in ('CONFIRMED', 'IN_PROGRESS')),
                       count(*) filter (where status = 'CANCELLED'),
                       count(*) filter (where status = 'NO_SHOW'),
                       count(*) filter (where channel = 'APP' and created_at >= ?),
                       count(*) filter (where channel = 'KIOSK' and created_at >= ?),
                       (select count(*) from service_jobs where created_at >= ?)
                from orders where status <> 'EXPIRED'
                """, (rs, i) -> map("total", rs.getLong(1), "inPeriod", rs.getLong(2), "completed", rs.getLong(3),
                "open", rs.getLong(4), "cancelled", rs.getLong(5), "noShow", rs.getLong(6), "appInPeriod", rs.getLong(7),
                "kioskInPeriod", rs.getLong(8), "counterJobsInPeriod", rs.getLong(9)), since, since, since, since);

        Map<String, Long> money = jdbc.queryForObject("""
                select (select coalesce(sum(amount), 0) from payments where status = 'CAPTURED'),
                       (select coalesce(sum(amount), 0) from payments where status = 'CAPTURED' and received_at >= ?),
                       (select count(*) from payments where status = 'CAPTURED' and received_at >= ?),
                       (select coalesce(sum(amount), 0) from refunds where status in ('APPROVED', 'COMPLETED') and created_at >= ?),
                       (select count(*) from refunds where status = 'PENDING_APPROVAL'),
                       (select coalesce(sum(amount), 0) from service_jobs where paid_at >= ?),
                       (select coalesce(sum(balance), 0) from wallets)
                """, (rs, i) -> map("capturedTotalVnd", rs.getLong(1), "capturedInPeriodVnd", rs.getLong(2),
                "paymentsInPeriod", rs.getLong(3), "refundedInPeriodVnd", rs.getLong(4),
                "refundsAwaitingApproval", rs.getLong(5), "counterInPeriodVnd", rs.getLong(6),
                "walletBalanceVnd", rs.getLong(7)), since, since, since, since);

        Map<String, Long> service = jdbc.queryForObject("""
                select (select count(*) from machines where admin_status <> 'RETIRED'),
                       (select count(*) from machines where admin_status = 'ACTIVE'),
                       (select count(*) from machines where admin_status in ('ERROR', 'MAINTENANCE')),
                       (select count(*) from devices where link_state = 'BOUND'),
                       (select count(*) from devices where link_state = 'BOUND' and online),
                       (select count(*) from devices where link_state = 'PENDING'),
                       (select count(*) from incidents where status in ('OPEN', 'INVESTIGATING')),
                       (select count(*) from incidents where status in ('OPEN', 'INVESTIGATING') and severity in ('HIGH', 'CRITICAL'))
                """, (rs, i) -> map("machines", rs.getLong(1), "machinesActive", rs.getLong(2),
                "machinesDown", rs.getLong(3), "devicesBound", rs.getLong(4), "devicesOnline", rs.getLong(5),
                "devicesPending", rs.getLong(6), "openIncidents", rs.getLong(7), "seriousIncidents", rs.getLong(8)));

        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        LocalDate today = LocalDate.ofInstant(now, zone);
        for (int i = span - 1; i >= 0; i--) byDay.put(today.minusDays(i), new long[3]);
        String tz = zone.getId();
        jdbc.query("select (created_at at time zone ?)::date, count(*) from orders where created_at >= ? and status <> 'EXPIRED' group by 1",
                rs -> { long[] v = byDay.get(rs.getObject(1, LocalDate.class)); if (v != null) v[0] = rs.getLong(2); }, tz, since);
        jdbc.query("""
                select d, sum(v) from (
                    select (received_at at time zone ?)::date d, amount v from payments where status = 'CAPTURED' and received_at >= ?
                    union all
                    select (paid_at at time zone ?)::date, amount from service_jobs where paid_at >= ?
                    union all
                    select (created_at at time zone ?)::date, -amount from refunds where status in ('APPROVED', 'COMPLETED') and created_at >= ?
                ) x group by d
                """, rs -> { long[] v = byDay.get(rs.getObject(1, LocalDate.class)); if (v != null) v[1] = rs.getLong(2); },
                tz, since, tz, since, tz, since);
        jdbc.query("select (created_at at time zone ?)::date, count(*) from users where created_at >= ? group by 1",
                rs -> { long[] v = byDay.get(rs.getObject(1, LocalDate.class)); if (v != null) v[2] = rs.getLong(2); }, tz, since);
        List<DayPoint> daily = byDay.entrySet().stream()
                .map(e -> new DayPoint(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2])).toList();
        return new Stats(now, users, orders, money, service, daily);
    }

    private static Map<String, Long> map(Object... kv) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Long) kv[i + 1]);
        return m;
    }

    // ---- Lịch sử đăng nhập ----

    @GetMapping("/api/admin/login-events")
    @Operation(summary = "Lịch sử đăng nhập, mới nhất trước; q lọc theo email / IP / tên; success lọc thành công/thất bại")
    public PageResponse<LoginEventService.LoginEventView> logins(@RequestParam(required = false) String q,
                                                                @RequestParam(required = false) Boolean success,
                                                                @RequestParam(required = false) UUID userId,
                                                                @RequestParam(defaultValue = "0") int page,
                                                                @RequestParam(defaultValue = "50") int size) {
        return loginEvents.search(q, success, userId, page, size);
    }

    @GetMapping("/api/admin/login-events/stats")
    public LoginEventService.LoginStats loginStats() {
        return loginEvents.stats();
    }
}
