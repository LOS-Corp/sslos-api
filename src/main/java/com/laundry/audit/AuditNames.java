package com.laundry.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tên dễ đọc cho một trang nhật ký: tên người làm (tài khoản, kiosk) và tên đối tượng (mã máy, tên profile, mã đơn…).
 * Mỗi loại tra một câu cho cả trang. Bản ghi đã xóa (máy, profile…) thì không có tên, giao diện hiện mã.
 */
@Component
@RequiredArgsConstructor
class AuditNames {

    private final NamedParameterJdbcTemplate jdbc;

    /** entity_type → câu tra tên theo danh sách id (:ids, uuid). */
    private static final Map<String, String> ENTITY_SQL = Map.of(
            "machine", "select id::text as id, code as label from machines where id in (:ids)",
            "price_profile", "select id::text as id, name as label from price_profiles where id in (:ids)",
            "locker", "select id::text as id, coalesce(name, code) as label from lockers where id in (:ids)",
            "kiosk", "select id::text as id, coalesce(name, code) as label from kiosks where id in (:ids)",
            "user", "select id::text as id, full_name as label from users where id in (:ids)",
            "order", "select id::text as id, code as label from orders where id in (:ids)",
            "refund", "select r.id::text as id, 'Đơn ' || o.code as label from refunds r join orders o on o.id = r.order_id where r.id in (:ids)",
            "payment", "select p.id::text as id, 'Đơn ' || o.code as label from payment_intents p join orders o on o.id = p.order_id where p.id in (:ids)",
            "machine_brand", "select id::text as id, name as label from machine_brands where id in (:ids)",
            "machine_model", "select id::text as id, name as label from machine_models where id in (:ids)");

    record Names(Map<UUID, String> users, Map<String, String> kiosks, Map<String, Map<String, String>> entities) {
        String actor(AuditLog l) {
            if (l.getActorUserId() != null) return users.get(l.getActorUserId());
            if ("KIOSK".equals(l.getActorType()) && l.getActorRef() != null) return kiosks.get(l.getActorRef());
            return null;
        }

        String entity(AuditLog l) {
            if (l.getEntityType() == null || l.getEntityId() == null) return null;
            return entities.getOrDefault(l.getEntityType(), Map.of()).get(l.getEntityId());
        }
    }

    Names of(Collection<AuditLog> logs) {
        Set<UUID> userIds = new HashSet<>();
        Set<UUID> kioskIds = new HashSet<>();
        Map<String, Set<UUID>> byType = new HashMap<>();
        for (AuditLog l : logs) {
            if (l.getActorUserId() != null) userIds.add(l.getActorUserId());
            if ("KIOSK".equals(l.getActorType())) uuid(l.getActorRef()).ifPresent(kioskIds::add);
            if (l.getEntityType() != null && ENTITY_SQL.containsKey(l.getEntityType())) {
                uuid(l.getEntityId()).ifPresent(id -> byType.computeIfAbsent(l.getEntityType(), k -> new HashSet<>()).add(id));
            }
        }
        Map<UUID, String> users = new HashMap<>();
        lookup(ENTITY_SQL.get("user"), userIds).forEach((id, name) -> users.put(UUID.fromString(id), name));
        Map<String, Map<String, String>> entities = new HashMap<>();
        byType.forEach((type, ids) -> entities.put(type, lookup(ENTITY_SQL.get(type), ids)));
        return new Names(users, lookup(ENTITY_SQL.get("kiosk"), kioskIds), entities);
    }

    private Map<String, String> lookup(String sql, Set<UUID> ids) {
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        List<Map<String, Object>> rows = jdbc.queryForList(sql, new MapSqlParameterSource("ids", ids));
        for (Map<String, Object> r : rows) {
            Object label = r.get("label");
            if (label != null) out.put((String) r.get("id"), label.toString());
        }
        return out;
    }

    private static java.util.Optional<UUID> uuid(String value) {
        if (value == null) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }
}
