package com.laundry.report;

import com.laundry.common.PageResponse;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.store.StoreService;
import com.laundry.store.StoreSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chủ cửa hàng: báo cáo doanh thu, lợi nhuận ước tính, hiệu suất máy; danh sách giao dịch; dự báo nhu cầu theo giờ.
 * Doanh thu = tiền thực nhận (payments CAPTURED, dịch vụ tại quầy đã thu) − tiền đã hoàn. Lợi nhuận ước tính =
 * doanh thu − số lượt máy × chi phí một lượt (Chủ cửa hàng nhập trong tham số cửa hàng).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Chủ cửa hàng · Báo cáo")
public class ReportController {

    private static final int MAX_DAYS = 366;

    private final JdbcTemplate jdbc;
    private final StoreService store;
    private final Clock clock;

    // ==================================================================================
    // Doanh thu, lợi nhuận, hiệu suất
    // ==================================================================================

    public record Totals(long machineRevenueVnd, long counterRevenueVnd, long grossVnd, long refundedVnd, long netVnd,
                         long runs, long washRuns, long dryRuns, long estimatedCostVnd, long estimatedProfitVnd,
                         long orders, long completedOrders, long cancelledOrders, long noShowOrders,
                         long counterJobs, long averageOrderVnd) {
    }

    public record DayRow(LocalDate date, long grossVnd, long refundedVnd, long netVnd, long runs, long orders) {
    }

    public record ServiceRow(String service, long revenueVnd, long count) {
    }

    public record MachineRow(UUID machineId, String code, String displayName, String type, long runs, long runMinutes,
                             long revenueVnd, long interrupted, double utilizationPercent) {
    }

    public record HourRow(int hour, long runs, double utilizationPercent) {
    }

    public record RevenueReport(LocalDate from, LocalDate to, String timezone, long openMinutes, Totals totals,
                                Totals previous, List<DayRow> daily, List<ServiceRow> byService,
                                List<MachineRow> byMachine, List<HourRow> byHour, long washCostPerRunVnd,
                                long dryCostPerRunVnd) {
    }

    @GetMapping("/api/owner/reports/revenue")
    @Operation(summary = "Doanh thu, lợi nhuận ước tính, hiệu suất máy trong [from, to] (ngày theo giờ cửa hàng); kèm kỳ trước cùng độ dài")
    @Transactional(readOnly = true)
    public RevenueReport revenue(@RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to) {
        StoreSettings s = store.settings();
        ZoneId zone = s.zone();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(29);
        if (end.isBefore(start)) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Ngày kết thúc phải sau ngày bắt đầu");
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > MAX_DAYS) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Tối đa " + MAX_DAYS + " ngày mỗi báo cáo");
        Timestamp a = ts(start, zone), b = ts(end.plusDays(1), zone);
        String tz = zone.getId();

        Totals totals = totals(s, a, b);
        Totals previous = totals(s, ts(start.minusDays(span), zone), a);

        Map<LocalDate, long[]> days = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) days.put(d, new long[4]);
        jdbc.query("""
                select d, sum(gross), sum(refunded) from (
                    select (received_at at time zone ?)::date d, amount gross, 0 refunded from payments
                    where status = 'CAPTURED' and received_at >= ? and received_at < ?
                    union all
                    select (paid_at at time zone ?)::date, amount, 0 from service_jobs
                    where paid_at >= ? and paid_at < ? and status <> 'CANCELLED'
                    union all
                    select (created_at at time zone ?)::date, 0, amount from refunds
                    where status in ('APPROVED', 'COMPLETED') and created_at >= ? and created_at < ?
                ) x group by d
                """, rs -> {
            long[] v = days.get(rs.getObject(1, LocalDate.class));
            if (v != null) { v[0] = rs.getLong(2); v[1] = rs.getLong(3); }
        }, tz, a, b, tz, a, b, tz, a, b);
        jdbc.query("""
                select (started_at at time zone ?)::date, count(*) from order_legs
                where started_at >= ? and started_at < ? group by 1
                """, rs -> {
            long[] v = days.get(rs.getObject(1, LocalDate.class));
            if (v != null) v[2] = rs.getLong(2);
        }, tz, a, b);
        jdbc.query("""
                select (created_at at time zone ?)::date, count(*) from orders
                where created_at >= ? and created_at < ? and status not in ('HOLD', 'EXPIRED') group by 1
                """, rs -> {
            long[] v = days.get(rs.getObject(1, LocalDate.class));
            if (v != null) v[3] = rs.getLong(2);
        }, tz, a, b);
        List<DayRow> daily = days.entrySet().stream().map(e -> new DayRow(e.getKey(), e.getValue()[0],
                e.getValue()[1], e.getValue()[0] - e.getValue()[1], e.getValue()[2], e.getValue()[3])).toList();

        List<ServiceRow> byService = new ArrayList<>(jdbc.query("""
                select o.service, coalesce(sum(p.amount), 0), count(distinct o.id) from payments p join orders o on o.id = p.order_id
                where p.status = 'CAPTURED' and p.received_at >= ? and p.received_at < ? group by o.service
                """, (rs, i) -> new ServiceRow(rs.getString(1), rs.getLong(2), rs.getLong(3)), a, b));
        byService.addAll(jdbc.query("""
                select service, coalesce(sum(amount), 0), count(*) from service_jobs
                where paid_at >= ? and paid_at < ? and status <> 'CANCELLED' group by service
                """, (rs, i) -> new ServiceRow(rs.getString(1), rs.getLong(2), rs.getLong(3)), a, b));
        byService.sort(Comparator.comparingLong(ServiceRow::revenueVnd).reversed());

        long openMinutes = openMinutes(start, end);
        List<MachineRow> byMachine = jdbc.query("""
                select m.id, m.code, m.display_name, m.machine_type,
                       count(l.id) filter (where l.started_at is not null),
                       coalesce(sum(extract(epoch from (coalesce(l.finished_at, least(l.expected_end_at, now())) - l.started_at)) / 60)
                                filter (where l.started_at is not null), 0)::bigint,
                       coalesce(sum(l.price_amount) filter (where l.status in ('RUNNING', 'AWAITING_UNLOAD', 'DONE')), 0),
                       count(l.id) filter (where l.status = 'INTERRUPTED')
                from machines m
                left join order_legs l on l.machine_id = m.id and l.started_at >= ? and l.started_at < ?
                where m.admin_status <> 'RETIRED' or l.id is not null
                group by m.id, m.code, m.display_name, m.machine_type, m.display_order
                order by m.machine_type desc, m.display_order, m.code
                """, (rs, i) -> {
            long minutes = Math.max(0, rs.getLong(6));
            return new MachineRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getLong(5), minutes, rs.getLong(7), rs.getLong(8), percent(minutes, openMinutes));
        }, a, b);

        long machines = Math.max(1, byMachine.size());
        long[] hourRuns = new long[24];
        jdbc.query("""
                select extract(hour from started_at at time zone ?)::int, count(*) from order_legs
                where started_at >= ? and started_at < ? group by 1
                """, rs -> { hourRuns[rs.getInt(1)] = rs.getLong(2); }, tz, a, b);
        double avgCycle = averageCycle(a, b);
        List<HourRow> byHour = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            double busy = hourRuns[h] * avgCycle;
            byHour.add(new HourRow(h, hourRuns[h], percent((long) busy, span * 60 * machines)));
        }
        return new RevenueReport(start, end, tz, openMinutes, totals, previous, daily, byService, byMachine, byHour,
                s.getWashCostPerRunVnd(), s.getDryCostPerRunVnd());
    }

    private Totals totals(StoreSettings s, Timestamp a, Timestamp b) {
        return jdbc.queryForObject("""
                select (select coalesce(sum(amount), 0) from payments where status = 'CAPTURED' and received_at >= ? and received_at < ?),
                       (select coalesce(sum(amount), 0) from service_jobs where paid_at >= ? and paid_at < ? and status <> 'CANCELLED'),
                       (select coalesce(sum(amount), 0) from refunds where status in ('APPROVED', 'COMPLETED') and created_at >= ? and created_at < ?),
                       (select count(*) filter (where machine_type = 'WASHER') from order_legs where started_at >= ? and started_at < ?),
                       (select count(*) filter (where machine_type = 'DRYER') from order_legs where started_at >= ? and started_at < ?),
                       (select count(*) from orders where created_at >= ? and created_at < ? and status not in ('HOLD', 'EXPIRED')),
                       (select count(*) from orders where created_at >= ? and created_at < ? and status = 'COMPLETED'),
                       (select count(*) from orders where created_at >= ? and created_at < ? and status = 'CANCELLED'),
                       (select count(*) from orders where created_at >= ? and created_at < ? and status = 'NO_SHOW'),
                       (select count(*) from service_jobs where created_at >= ? and created_at < ? and status <> 'CANCELLED')
                """, (rs, i) -> {
            long machine = rs.getLong(1), counter = rs.getLong(2), refunded = rs.getLong(3);
            long wash = rs.getLong(4), dry = rs.getLong(5);
            long gross = machine + counter, net = gross - refunded;
            long cost = wash * s.getWashCostPerRunVnd() + dry * s.getDryCostPerRunVnd();
            long orders = rs.getLong(6), jobs = rs.getLong(10);
            long count = orders + jobs;
            return new Totals(machine, counter, gross, refunded, net, wash + dry, wash, dry, cost, net - cost, orders,
                    rs.getLong(7), rs.getLong(8), rs.getLong(9), jobs, count == 0 ? 0 : Math.round(gross / (double) count));
        }, a, b, a, b, a, b, a, b, a, b, a, b, a, b, a, b, a, b, a, b);
    }

    /** Tổng số phút mở cửa trong khoảng ngày theo khung giờ OPEN (không trừ ngày nghỉ đột xuất). */
    private long openMinutes(LocalDate start, LocalDate end) {
        Map<Integer, Long> perDay = new HashMap<>();
        jdbc.query("select day_of_week, opens_at, closes_at from store_hours where kind = 'OPEN'", rs -> {
            LocalTime open = rs.getObject(2, LocalTime.class), close = rs.getObject(3, LocalTime.class);
            perDay.merge(rs.getInt(1), Duration.between(open, close).toMinutes(), Long::sum);
        });
        long total = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) total += perDay.getOrDefault(d.getDayOfWeek().getValue(), 0L);
        return total;
    }

    private double averageCycle(Timestamp a, Timestamp b) {
        Double avg = jdbc.queryForObject("select avg(cycle_minutes + extra_minutes) from order_legs where started_at >= ? and started_at < ?",
                Double.class, a, b);
        if (avg != null) return avg;
        Double machines = jdbc.queryForObject("select avg(cycle_minutes) from machines where admin_status = 'ACTIVE'", Double.class);
        return machines == null ? 45 : machines;
    }

    // ==================================================================================
    // Giao dịch
    // ==================================================================================

    public record TransactionRow(String source, UUID id, String reference, String orderCode, String service,
                                 String method, String status, long amountVnd, Instant at, String note) {
    }

    public record TransactionPage(PageResponse<TransactionRow> page, long capturedVnd, long rejectedVnd,
                                  long counterVnd, long refundedVnd) {
    }

    @GetMapping("/api/owner/payments")
    @Operation(summary = "Giao dịch thu tiền (payOS, ví, tại quầy) và hoàn tiền trong khoảng ngày; kind=IN|REFUND|ALL")
    @Transactional(readOnly = true)
    public TransactionPage payments(@RequestParam(required = false) LocalDate from,
                                    @RequestParam(required = false) LocalDate to,
                                    @RequestParam(defaultValue = "ALL") String kind,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "50") int size) {
        ZoneId zone = store.settings().zone();
        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), zone);
        LocalDate start = from != null ? from : end.minusDays(6);
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Khoảng ngày không hợp lệ (tối đa " + MAX_DAYS + " ngày)");
        }
        Timestamp a = ts(start, zone), b = ts(end.plusDays(1), zone);
        String union = """
                select 'PAYMENT'::text source, p.id, p.receipt_no::text reference, o.code::text order_code,
                       o.service::text service, p.method::text method, p.status::text status, p.amount,
                       p.received_at at_time, p.reject_reason::text note
                from payments p left join orders o on o.id = p.order_id
                where p.received_at >= ? and p.received_at < ?
                union all
                select 'COUNTER', j.id, j.code, j.code, j.service, j.payment_method,
                       case when j.status = 'CANCELLED' then 'REFUNDED_AT_COUNTER' else 'CAPTURED' end, j.amount, j.paid_at,
                       j.customer_name
                from service_jobs j where j.paid_at >= ? and j.paid_at < ?
                union all
                select 'REFUND', r.id, r.reason_code, o.code::text, o.service, r.destination, r.status, r.amount, r.created_at,
                       r.reason_text
                from refunds r left join orders o on o.id = r.order_id
                where r.created_at >= ? and r.created_at < ?
                """;
        String filter = switch (kind.toUpperCase()) {
            case "IN" -> " where source in ('PAYMENT', 'COUNTER')";
            case "REFUND" -> " where source = 'REFUND'";
            default -> " where true";
        };
        List<Object> args = new ArrayList<>(List.of(a, b, a, b, a, b));
        if (q != null && !q.isBlank()) {
            filter += " and (lower(coalesce(reference, '')) like ? or lower(coalesce(order_code, '')) like ? or lower(coalesce(note, '')) like ?)";
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        int limit = Math.min(Math.max(size, 1), 200);
        int offset = Math.max(page, 0) * limit;
        String base = "from (" + union + ") t" + filter;
        Long total = jdbc.queryForObject("select count(*) " + base, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<TransactionRow> rows = jdbc.query("select * " + base + " order by at_time desc limit ? offset ?",
                (rs, i) -> new TransactionRow(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8),
                        rs.getTimestamp(9).toInstant(), rs.getString(10)), pageArgs.toArray());
        long[] sums = new long[4];
        jdbc.query("select source, status, coalesce(sum(amount), 0) from (" + union + ") t group by source, status", rs -> {
            String source = rs.getString(1), status = rs.getString(2);
            long amount = rs.getLong(3);
            if ("PAYMENT".equals(source) && "CAPTURED".equals(status)) sums[0] += amount;
            if ("PAYMENT".equals(source) && "REJECTED".equals(status)) sums[1] += amount;
            if ("COUNTER".equals(source) && "CAPTURED".equals(status)) sums[2] += amount;
            if ("REFUND".equals(source) && ("APPROVED".equals(status) || "COMPLETED".equals(status))) sums[3] += amount;
        }, a, b, a, b, a, b);
        long totalItems = total == null ? 0 : total;
        return new TransactionPage(new PageResponse<>(rows, Math.max(page, 0), limit, totalItems,
                (int) Math.ceil(totalItems / (double) limit)), sums[0], sums[1], sums[2], sums[3]);
    }

    // ==================================================================================
    // Dự báo nhu cầu (thống kê, không phải mô hình AI)
    // ==================================================================================

    public record HourForecast(int hour, double expectedRuns, double utilizationPercent, boolean open) {
    }

    public record DayForecast(LocalDate date, DayOfWeek dayOfWeek, double expectedRuns, List<HourForecast> hours,
                              List<Integer> peakHours) {
    }

    /** heatmap[d][h]: số lượt trung bình có trọng số, d = 0 thứ Hai ... 6 Chủ nhật. */
    public record Forecast(String method, int historyWeeks, long historyRuns, String confidence, double trendFactor,
                           int machines, double averageCycleMinutes, List<DayForecast> days, double[][] heatmap,
                           List<String> notes) {
    }

    @GetMapping("/api/owner/reports/forecast")
    @Operation(summary = "Dự báo số lượt máy theo giờ cho `days` ngày tới, từ lịch sử `weeks` tuần (trung bình có trọng số)")
    @Transactional(readOnly = true)
    public Forecast forecast(@RequestParam(defaultValue = "7") int days, @RequestParam(defaultValue = "8") int weeks) {
        int horizon = Math.max(1, Math.min(days, 14));
        int history = Math.max(2, Math.min(weeks, 26));
        StoreSettings s = store.settings();
        ZoneId zone = s.zone();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate historyStart = today.minusWeeks(history);
        Timestamp a = ts(historyStart, zone), b = ts(today, zone);

        // counts[w][d][h]: tuần w (0 = gần nhất), thứ d, giờ h. Lượt tính theo giờ bắt đầu dự kiến của chặng.
        long[][][] counts = new long[history][7][24];
        long[] total = new long[1];
        jdbc.query("""
                select (l.planned_start_at at time zone ?)::date, extract(hour from l.planned_start_at at time zone ?)::int, count(*)
                from order_legs l join orders o on o.id = l.order_id
                where l.planned_start_at >= ? and l.planned_start_at < ? and o.status not in ('HOLD', 'EXPIRED')
                group by 1, 2
                """, rs -> {
            LocalDate d = rs.getObject(1, LocalDate.class);
            int week = (int) (ChronoUnit.DAYS.between(d, today) - 1) / 7;
            if (week < 0 || week >= history) return;
            long n = rs.getLong(3);
            counts[week][d.getDayOfWeek().getValue() - 1][rs.getInt(2)] += n;
            total[0] += n;
        }, zone.getId(), zone.getId(), a, b);

        double[][] heat = new double[7][24];
        double weightSum = 0;
        for (int w = 0; w < history; w++) weightSum += history - w;
        for (int d = 0; d < 7; d++) {
            for (int h = 0; h < 24; h++) {
                double v = 0;
                for (int w = 0; w < history; w++) v += (history - w) * counts[w][d][h];
                heat[d][h] = round2(v / weightSum);
            }
        }

        // Xu hướng: 2 tuần gần nhất so với trung bình các tuần trước, giới hạn 0,7–1,3 để không phóng đại.
        double recent = 0, older = 0;
        for (int w = 0; w < history; w++) {
            long sum = 0;
            for (int d = 0; d < 7; d++) for (int h = 0; h < 24; h++) sum += counts[w][d][h];
            if (w < 2) recent += sum; else older += sum;
        }
        double trend = 1.0;
        if (history > 2 && older > 0) {
            double ratio = (recent / 2.0) / (older / (history - 2.0));
            trend = Math.max(0.7, Math.min(1.3, ratio));
        }
        trend = round2(trend);

        Map<Integer, boolean[]> openHours = new HashMap<>();
        jdbc.query("select day_of_week, opens_at, closes_at from store_hours where kind = 'OPEN'", rs -> {
            boolean[] hours = openHours.computeIfAbsent(rs.getInt(1), k -> new boolean[24]);
            LocalTime open = rs.getObject(2, LocalTime.class), close = rs.getObject(3, LocalTime.class);
            for (int h = 0; h < 24; h++) {
                LocalTime slotStart = LocalTime.of(h, 0);
                LocalTime slotEnd = h == 23 ? LocalTime.MAX : LocalTime.of(h + 1, 0);
                if (open.isBefore(slotEnd) && close.isAfter(slotStart)) hours[h] = true;
            }
        });
        Integer machineCount = jdbc.queryForObject("select count(*) from machines where admin_status = 'ACTIVE'", Integer.class);
        int machines = machineCount == null ? 0 : machineCount;
        Double cycle = jdbc.queryForObject("select avg(cycle_minutes) from machines where admin_status = 'ACTIVE'", Double.class);
        double avgCycle = cycle == null ? 45 : cycle;

        List<DayForecast> out = new ArrayList<>();
        for (int i = 0; i < horizon; i++) {
            LocalDate date = today.plusDays(i);
            int d = date.getDayOfWeek().getValue() - 1;
            boolean[] open = openHours.getOrDefault(d + 1, new boolean[24]);
            List<HourForecast> hours = new ArrayList<>();
            double sum = 0;
            for (int h = 0; h < 24; h++) {
                double expected = round2(heat[d][h] * trend);
                sum += expected;
                double util = machines == 0 ? 0 : Math.min(100, round2(expected * avgCycle / (60.0 * machines) * 100));
                hours.add(new HourForecast(h, expected, util, open[h]));
            }
            List<Integer> peaks = hours.stream().filter(x -> x.expectedRuns() > 0)
                    .sorted(Comparator.comparingDouble(HourForecast::expectedRuns).reversed())
                    .limit(3).map(HourForecast::hour).sorted().toList();
            out.add(new DayForecast(date, date.getDayOfWeek(), round2(sum), hours, peaks));
        }

        List<String> notes = new ArrayList<>();
        String confidence;
        if (total[0] < 30) {
            confidence = "LOW";
            notes.add("Lịch sử còn ít (" + total[0] + " lượt trong " + history + " tuần): dự báo chỉ mang tính tham khảo.");
        } else if (total[0] < 200) {
            confidence = "MEDIUM";
        } else {
            confidence = "HIGH";
        }
        if (trend != 1.0) notes.add("Hai tuần gần nhất " + (trend > 1 ? "đông hơn" : "vắng hơn") + " trung bình; đã nhân hệ số " + trend + ".");
        notes.add("Không tính ngày lễ, thời tiết hay khuyến mãi; ngày nghỉ đột xuất vẫn hiện dự báo.");
        return new Forecast("Trung bình có trọng số theo thứ và giờ trong " + history + " tuần gần nhất (tuần gần hơn nặng hơn), nhân hệ số xu hướng",
                history, total[0], confidence, trend, machines, round2(avgCycle), out, heat, notes);
    }

    private static Timestamp ts(LocalDate date, ZoneId zone) {
        return Timestamp.from(date.atStartOfDay(zone).toInstant());
    }

    private static double percent(long part, long whole) {
        return whole <= 0 ? 0 : Math.min(100, round2(part * 100.0 / whole));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
