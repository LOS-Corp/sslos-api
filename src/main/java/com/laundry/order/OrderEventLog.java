package com.laundry.order;

import com.laundry.security.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lịch sử vòng đời đơn (order_events, chỉ thêm). */
@Component
@RequiredArgsConstructor
public class OrderEventLog {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public void record(UUID orderId, String type, Order.Status from, Order.Status to, Map<String, ?> data) {
        String actorType = "SYSTEM";
        String actorId = null;
        Actor actor = Actor.current().orElse(null);
        if (actor instanceof Actor.User u) {
            actorType = "USER";
            actorId = u.id().toString();
        } else if (actor instanceof Actor.Kiosk k) {
            actorType = "KIOSK";
            actorId = k.id().toString();
        }
        jdbc.update("""
                insert into order_events (order_id, event_type, from_status, to_status, data, actor_type, actor_id)
                values (?, ?, ?, ?, cast(? as jsonb), ?, ?)
                """, orderId, type, from == null ? null : from.name(), to == null ? null : to.name(),
                data == null ? null : mapper.writeValueAsString(data), actorType, actorId);
    }

    public void device(UUID orderId, String type, String deviceId, Map<String, ?> data) {
        jdbc.update("""
                insert into order_events (order_id, event_type, data, actor_type, actor_id)
                values (?, ?, cast(? as jsonb), 'DEVICE', ?)
                """, orderId, type, data == null ? null : mapper.writeValueAsString(data), deviceId);
    }

    public record EventView(String type, String fromStatus, String toStatus, String data, String actorType,
                            java.time.Instant at) {
    }

    public List<EventView> history(UUID orderId) {
        return jdbc.query("""
                select event_type, from_status, to_status, data::text, actor_type, created_at
                from order_events where order_id = ? order by id
                """, (rs, i) -> new EventView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getTimestamp(6).toInstant()), orderId);
    }
}
