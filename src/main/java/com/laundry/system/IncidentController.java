package com.laundry.system;

import com.laundry.audit.AuditService;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Quản lý sự cố cấp hệ thống (lỗi hệ thống, sự cố bảo mật, thiết bị, thanh toán, dữ liệu): ghi nhận, phân công,
 * theo dõi diễn biến và đóng kèm kết luận. Mọi thao tác ghi nhật ký thao tác.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Admin · Sự cố")
public class IncidentController {

    public enum Category { SYSTEM, SECURITY, DEVICE, PAYMENT, DATA, OTHER }

    public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    public enum Status { OPEN, INVESTIGATING, RESOLVED, CLOSED }

    private static final Map<Status, Set<Status>> NEXT = Map.of(
            Status.OPEN, Set.of(Status.INVESTIGATING, Status.RESOLVED, Status.CLOSED),
            Status.INVESTIGATING, Set.of(Status.OPEN, Status.RESOLVED, Status.CLOSED),
            Status.RESOLVED, Set.of(Status.INVESTIGATING, Status.CLOSED),
            Status.CLOSED, Set.of(Status.INVESTIGATING));

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public record IncidentView(UUID id, String code, String title, String description, Category category,
                               Severity severity, Status status, UUID reportedBy, String reportedByName,
                               UUID assigneeId, String assigneeName, String resolution, Instant occurredAt,
                               Instant resolvedAt, Instant createdAt, Instant updatedAt) {
    }

    public record NoteView(long id, String kind, String body, String fromStatus, String toStatus, UUID authorId,
                           String authorName, Instant createdAt) {
    }

    public record DetailView(IncidentView incident, List<NoteView> notes) {
    }

    public record CreateBody(@NotBlank @Size(max = 160) String title, @Size(max = 2000) String description,
                             @NotNull Category category, @NotNull Severity severity, Instant occurredAt,
                             UUID assigneeId) {
    }

    public record UpdateBody(@Size(max = 160) String title, @Size(max = 2000) String description, Category category,
                             Severity severity, UUID assigneeId, Boolean clearAssignee, @NotBlank String reason) {
    }

    /** resolution bắt buộc khi chuyển sang RESOLVED / CLOSED. */
    public record StatusBody(@NotNull Status status, @Size(max = 2000) String resolution, @NotBlank String reason) {
    }

    public record NoteBody(@NotBlank @Size(max = 2000) String body) {
    }

    private static final String SELECT = """
            select i.id, i.code, i.title, i.description, i.category, i.severity, i.status, i.reported_by, r.full_name,
                   i.assignee_id, a.full_name, i.resolution, i.occurred_at, i.resolved_at, i.created_at, i.updated_at
            from incidents i join users r on r.id = i.reported_by left join users a on a.id = i.assignee_id
            """;

    private static final RowMapper<IncidentView> ROW = (rs, i) -> new IncidentView(rs.getObject(1, UUID.class),
            rs.getString(2), rs.getString(3), rs.getString(4), Category.valueOf(rs.getString(5)),
            Severity.valueOf(rs.getString(6)), Status.valueOf(rs.getString(7)), rs.getObject(8, UUID.class),
            rs.getString(9), rs.getObject(10, UUID.class), rs.getString(11), rs.getString(12),
            rs.getTimestamp(13).toInstant(), instant(rs.getTimestamp(14)), rs.getTimestamp(15).toInstant(),
            rs.getTimestamp(16).toInstant());

    @GetMapping("/api/admin/incidents")
    @Operation(summary = "Sự cố, mới nhất trước; status/severity/category để lọc, q tìm theo mã hoặc tiêu đề")
    @Transactional(readOnly = true)
    public List<IncidentView> list(@RequestParam(required = false) Set<Status> status,
                                   @RequestParam(required = false) Severity severity,
                                   @RequestParam(required = false) Category category,
                                   @RequestParam(required = false) String q) {
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            where.append(" and i.status in (").append(String.join(",", status.stream().map(s -> "?").toList())).append(")");
            status.forEach(s -> args.add(s.name()));
        }
        if (severity != null) {
            where.append(" and i.severity = ?");
            args.add(severity.name());
        }
        if (category != null) {
            where.append(" and i.category = ?");
            args.add(category.name());
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(i.code) like ? or lower(i.title) like ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
        }
        return jdbc.query(SELECT + where + """
                 order by case when i.status in ('OPEN', 'INVESTIGATING') then 0 else 1 end,
                          case i.severity when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,
                          i.occurred_at desc limit 500
                """, ROW, args.toArray());
    }

    @GetMapping("/api/admin/incidents/{id}")
    @Transactional(readOnly = true)
    public DetailView get(@PathVariable UUID id) {
        return new DetailView(load(id), notes(id));
    }

    @PostMapping("/api/admin/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DetailView create(@Valid @RequestBody CreateBody body) {
        UUID me = Actor.userId();
        if (body.assigneeId() != null) requireAdmin(body.assigneeId());
        Instant now = clock.instant();
        Instant occurred = body.occurredAt() == null ? now : body.occurredAt();
        if (occurred.isAfter(now.plusSeconds(60))) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Thời điểm xảy ra không được ở tương lai");
        Long seq = jdbc.queryForObject("select nextval('incident_code_seq')", Long.class);
        String code = String.format("INC-%05d", seq);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into incidents (id, code, title, description, category, severity, reported_by, assignee_id, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, code, body.title().trim(), blank(body.description()), body.category().name(),
                body.severity().name(), me, body.assigneeId(), Timestamp.from(occurred));
        addNote(id, me, "NOTE", "Ghi nhận sự cố", null, Status.OPEN.name());
        audit.user("INCIDENT_CREATED", "incident", id.toString(), "Ghi nhận sự cố " + code, null,
                Map.of("title", body.title().trim(), "severity", body.severity(), "category", body.category()));
        return get(id);
    }

    @PutMapping("/api/admin/incidents/{id}")
    @Transactional
    public DetailView update(@PathVariable UUID id, @Valid @RequestBody UpdateBody body) {
        String reason = Reasons.require(body.reason());
        IncidentView before = lock(id);
        Map<String, Object> b = new LinkedHashMap<>();
        Map<String, Object> a = new LinkedHashMap<>();
        if (body.title() != null && !body.title().isBlank() && !body.title().trim().equals(before.title())) {
            b.put("title", before.title());
            a.put("title", body.title().trim());
            jdbc.update("update incidents set title = ? where id = ?", body.title().trim(), id);
        }
        if (body.description() != null && !java.util.Objects.equals(blank(body.description()), before.description())) {
            b.put("description", before.description());
            a.put("description", blank(body.description()));
            jdbc.update("update incidents set description = ? where id = ?", blank(body.description()), id);
        }
        if (body.category() != null && body.category() != before.category()) {
            b.put("category", before.category());
            a.put("category", body.category());
            jdbc.update("update incidents set category = ? where id = ?", body.category().name(), id);
        }
        if (body.severity() != null && body.severity() != before.severity()) {
            b.put("severity", before.severity());
            a.put("severity", body.severity());
            jdbc.update("update incidents set severity = ? where id = ?", body.severity().name(), id);
        }
        UUID assignee = Boolean.TRUE.equals(body.clearAssignee()) ? null
                : body.assigneeId() != null ? body.assigneeId() : before.assigneeId();
        if (!java.util.Objects.equals(assignee, before.assigneeId())) {
            if (assignee != null) requireAdmin(assignee);
            b.put("assigneeId", before.assigneeId());
            a.put("assigneeId", assignee);
            jdbc.update("update incidents set assignee_id = ? where id = ?", assignee, id);
        }
        if (a.isEmpty()) return get(id);
        touch(id);
        addNote(id, Actor.userId(), "NOTE", "Cập nhật: " + String.join(", ", a.keySet()) + " — " + reason, null, null);
        audit.user("INCIDENT_UPDATED", "incident", id.toString(), reason, b, a);
        return get(id);
    }

    @PutMapping("/api/admin/incidents/{id}/status")
    @Transactional
    public DetailView status(@PathVariable UUID id, @Valid @RequestBody StatusBody body) {
        String reason = Reasons.require(body.reason());
        IncidentView before = lock(id);
        if (body.status() == before.status()) return get(id);
        if (!NEXT.get(before.status()).contains(body.status())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Không chuyển được từ " + before.status() + " sang " + body.status());
        }
        boolean closing = body.status() == Status.RESOLVED || body.status() == Status.CLOSED;
        String resolution = blank(body.resolution());
        if (closing && resolution == null && before.resolution() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Ghi kết luận xử lý trước khi đóng sự cố");
        }
        Instant now = clock.instant();
        jdbc.update("""
                update incidents set status = ?, resolution = coalesce(?, resolution),
                       resolved_at = case when ? then coalesce(resolved_at, ?) else null end
                where id = ?
                """, body.status().name(), resolution, closing, Timestamp.from(now), id);
        touch(id);
        addNote(id, Actor.userId(), "STATUS", resolution != null ? reason + " — Kết luận: " + resolution : reason,
                before.status().name(), body.status().name());
        audit.user("INCIDENT_STATUS_CHANGED", "incident", id.toString(), reason, Map.of("status", before.status()),
                Map.of("status", body.status()));
        return get(id);
    }

    @PostMapping("/api/admin/incidents/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public DetailView note(@PathVariable UUID id, @Valid @RequestBody NoteBody body) {
        load(id);
        addNote(id, Actor.userId(), "NOTE", body.body().trim(), null, null);
        touch(id);
        return get(id);
    }

    private IncidentView load(UUID id) {
        return jdbc.query(SELECT + " where i.id = ?", ROW, id).stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("sự cố"));
    }

    private IncidentView lock(UUID id) {
        jdbc.query("select id from incidents where id = ? for update", rs -> { }, id);
        return load(id);
    }

    private List<NoteView> notes(UUID id) {
        return jdbc.query("""
                select n.id, n.kind, n.body, n.from_status, n.to_status, n.author_id, u.full_name, n.created_at
                from incident_notes n join users u on u.id = n.author_id where n.incident_id = ? order by n.id
                """, (rs, i) -> new NoteView(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7), rs.getTimestamp(8).toInstant()), id);
    }

    private void addNote(UUID id, UUID author, String kind, String body, String from, String to) {
        String text = body.length() > 2000 ? body.substring(0, 2000) : body;
        jdbc.update("insert into incident_notes (incident_id, author_id, kind, body, from_status, to_status) values (?, ?, ?, ?, ?, ?)",
                id, author, kind, text, from, to);
    }

    private void touch(UUID id) {
        jdbc.update("update incidents set updated_at = ?, version = version + 1 where id = ?", Timestamp.from(clock.instant()), id);
    }

    private void requireAdmin(UUID userId) {
        Integer n = jdbc.queryForObject("select count(*) from user_roles where user_id = ? and role = 'ADMIN'", Integer.class, userId);
        if (n == null || n == 0) throw new BusinessException(ErrorCode.BAD_REQUEST, "Chỉ phân công sự cố cho Quản trị viên");
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
