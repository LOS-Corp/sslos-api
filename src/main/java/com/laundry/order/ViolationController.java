package com.laundry.order;

import com.laundry.common.error.BusinessException;
import com.laundry.store.StoreService;
import com.laundry.store.StoreSettings;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Quản lý vi phạm (§11.1): khách không đến hoặc hủy muộn đơn đặt trước. Chính sách tự động: đủ ngưỡng trong cửa sổ
 * thì phải trả 100% khi đặt trước trong thời gian phạt. Quản trị viên xem danh sách, và khi cần thì khóa hẳn quyền
 * đặt lịch (PUT /api/admin/users/{id}/booking-ban).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Admin · Vi phạm")
public class ViolationController {

    private final JdbcTemplate jdbc;
    private final StoreService store;
    private final Clock clock;

    /**
     * recentCount: số lần vi phạm trong cửa sổ đang tính; prepayPenalty: đang bị buộc trả 100% theo chính sách;
     * bookingBannedUntil: đang bị khóa đặt lịch thủ công.
     */
    public record ViolatorView(UUID userId, String fullName, String email, String phone, String accountStatus,
                               long totalCount, long recentCount, long noShowCount, long lateCancelCount,
                               Instant lastViolationAt, boolean prepayPenalty, Instant bookingBannedUntil,
                               String bookingBanReason) {
    }

    public record ViolationSummary(int threshold, int windowDays, int penaltyDays, long violators, long penalized,
                                   long banned, List<ViolatorView> items) {
    }

    public record ViolationOrder(UUID orderId, String code, String service, String cancellationKind,
                                 Instant scheduledStartAt, Instant cancelledAt, long paidAmount, long forfeitedAmount,
                                 String cancelReason) {
    }

    @GetMapping("/api/admin/violations")
    @Operation(summary = "Khách có vi phạm (không đến, hủy muộn) hoặc đang bị khóa đặt lịch; q lọc theo tên/email/SĐT")
    @Transactional(readOnly = true)
    public ViolationSummary list(@RequestParam(required = false) String q,
                                 @RequestParam(defaultValue = "false") boolean onlyActive) {
        StoreSettings s = store.settings();
        Instant now = clock.instant();
        Instant windowStart = now.minus(Duration.ofDays(s.getViolationWindowDays()));
        Instant since = now.minus(Duration.ofDays(s.getViolationWindowDays() + s.getViolationPenaltyDays()));
        String like = "%" + (q == null ? "" : q.trim().toLowerCase()) + "%";

        Map<UUID, List<Instant>> times = new HashMap<>();
        jdbc.query("""
                select customer_id, cancelled_at from orders
                where counts_as_violation and cancelled_at >= ? order by cancelled_at
                """, rs -> {
            times.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
                    .add(rs.getTimestamp(2).toInstant());
        }, Timestamp.from(since));

        List<ViolatorView> rows = jdbc.query("""
                with v as (
                    select customer_id,
                           count(*) as total,
                           count(*) filter (where cancelled_at >= ?) as recent,
                           count(*) filter (where cancellation_kind = 'NO_SHOW') as no_shows,
                           count(*) filter (where cancellation_kind = 'LATE') as late_cancels,
                           max(cancelled_at) as last_at
                    from orders where counts_as_violation group by customer_id)
                select u.id, u.full_name, u.email, u.phone, u.status,
                       coalesce(v.total, 0), coalesce(v.recent, 0), coalesce(v.no_shows, 0), coalesce(v.late_cancels, 0),
                       v.last_at, u.booking_banned_until, u.booking_ban_reason
                from users u left join v on v.customer_id = u.id
                where (v.customer_id is not null or u.booking_banned_until > ?)
                  and (lower(u.full_name) like ? or lower(u.email) like ? or u.phone like ?)
                order by coalesce(v.recent, 0) desc, v.last_at desc nulls last
                limit 500
                """, (rs, i) -> {
            UUID id = rs.getObject(1, UUID.class);
            Timestamp banned = rs.getTimestamp(11);
            Instant bannedUntil = banned == null || !banned.toInstant().isAfter(now) ? null : banned.toInstant();
            boolean penalty = ViolationPolicy.penaltyActive(times.getOrDefault(id, List.of()), now,
                    s.getViolationThreshold(), s.getViolationWindowDays(), s.getViolationPenaltyDays());
            Timestamp last = rs.getTimestamp(10);
            return new ViolatorView(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), last == null ? null : last.toInstant(),
                    penalty, bannedUntil, bannedUntil == null ? null : rs.getString(12));
        }, Timestamp.from(windowStart), Timestamp.from(now), like, like, like);

        List<ViolatorView> items = onlyActive
                ? rows.stream().filter(r -> r.prepayPenalty() || r.bookingBannedUntil() != null
                || r.recentCount() >= s.getViolationThreshold()).toList()
                : rows;
        return new ViolationSummary(s.getViolationThreshold(), s.getViolationWindowDays(), s.getViolationPenaltyDays(),
                rows.size(), rows.stream().filter(ViolatorView::prepayPenalty).count(),
                rows.stream().filter(r -> r.bookingBannedUntil() != null).count(), items);
    }

    @GetMapping("/api/admin/violations/{userId}")
    @Operation(summary = "Các đơn tính vi phạm của một khách, mới nhất trước")
    @Transactional(readOnly = true)
    public List<ViolationOrder> orders(@PathVariable UUID userId) {
        Integer exists = jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, userId);
        if (exists == null || exists == 0) throw BusinessException.notFound("tài khoản");
        return jdbc.query("""
                select id, code, service, cancellation_kind, scheduled_start_at, cancelled_at, paid_amount,
                       forfeited_amount, cancel_reason
                from orders where customer_id = ? and counts_as_violation order by cancelled_at desc limit 200
                """, (rs, i) -> new ViolationOrder(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getTimestamp(6).toInstant(), rs.getLong(7),
                rs.getLong(8), rs.getString(9)), userId);
    }
}
