package com.laundry.auth;

import com.laundry.common.PageResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lịch sử đăng nhập (Audit Logs → Đăng nhập): mọi lần đăng nhập, kể cả sai mật khẩu hoặc tài khoản bị khóa.
 * Ghi trong transaction riêng: lần thất bại vẫn được lưu dù thao tác đăng nhập bị hủy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginEventService {

    public enum Failure { UNKNOWN_EMAIL, WRONG_PASSWORD, ACCOUNT_LOCKED }

    public record LoginEventView(long id, Instant occurredAt, UUID userId, String fullName, String email,
                                 boolean success, String failureReason, String ipAddress, String userAgent) {
    }

    public record LoginStats(long success24h, long failed24h, long distinctUsers24h, long suspiciousIps24h) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID userId, String email, Failure failure, String ip, String userAgent) {
        try {
            jdbc.update("""
                    insert into login_events (user_id, email, success, failure_reason, ip_address, user_agent, occurred_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, userId, truncate(email.trim().toLowerCase(), 254), failure == null,
                    failure == null ? null : failure.name(), truncate(ip, 45), truncate(userAgent, 255),
                    Timestamp.from(clock.instant()));
        } catch (RuntimeException e) {
            // Không để lỗi ghi nhật ký chặn đăng nhập.
            log.warn("Could not record login event for {}: {}", email, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<LoginEventView> search(String q, Boolean success, UUID userId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 200);
        int offset = Math.max(page, 0) * limit;
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(e.email) like ? or e.ip_address like ? or lower(u.full_name) like ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (success != null) {
            where.append(" and e.success = ?");
            args.add(success);
        }
        if (userId != null) {
            where.append(" and e.user_id = ?");
            args.add(userId);
        }
        String from = " from login_events e left join users u on u.id = e.user_id";
        Long total = jdbc.queryForObject("select count(*)" + from + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<LoginEventView> items = jdbc.query("""
                select e.id, e.occurred_at, e.user_id, u.full_name, e.email, e.success, e.failure_reason,
                       e.ip_address, e.user_agent""" + from + where + " order by e.id desc limit ? offset ?",
                (rs, i) -> new LoginEventView(rs.getLong(1), rs.getTimestamp(2).toInstant(),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getBoolean(6),
                        rs.getString(7), rs.getString(8), rs.getString(9)), pageArgs.toArray());
        long totalItems = total == null ? 0 : total;
        return new PageResponse<>(items, Math.max(page, 0), limit, totalItems, (int) Math.ceil(totalItems / (double) limit));
    }

    /** suspiciousIps24h: IP sai mật khẩu từ 5 lần trở lên trong 24 giờ. */
    @Transactional(readOnly = true)
    public LoginStats stats() {
        Timestamp since = Timestamp.from(clock.instant().minusSeconds(86_400));
        return jdbc.queryForObject("""
                select count(*) filter (where success),
                       count(*) filter (where not success),
                       count(distinct user_id) filter (where success),
                       (select count(*) from (select ip_address from login_events
                                              where not success and occurred_at >= ? and ip_address is not null
                                              group by ip_address having count(*) >= 5) s)
                from login_events where occurred_at >= ?
                """, (rs, i) -> new LoginStats(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)), since, since);
    }

    private static String truncate(String value, int max) {
        return value == null ? null : value.substring(0, Math.min(max, value.length()));
    }
}
