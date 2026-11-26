package com.laundry.staff;

import com.laundry.audit.AuditService;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.security.Actor;
import com.laundry.security.Role;
import com.laundry.user.UserService;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Phân công nhiệm vụ: Chủ cửa hàng giao việc (vệ sinh máy, kiểm tồn, trực quầy...) cho nhân viên; nhân viên xem việc
 * của mình và cập nhật tiến độ.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Phân công nhiệm vụ")
public class TaskController {

    public enum Priority { LOW, NORMAL, HIGH }

    public enum Status { TODO, IN_PROGRESS, DONE, CANCELLED }

    private final JdbcTemplate jdbc;
    private final UserService users;
    private final AuditService audit;
    private final Clock clock;

    public record TaskView(UUID id, String title, String description, UUID assigneeId, String assigneeName,
                           Priority priority, Status status, Instant dueAt, UUID machineId, String machineCode,
                           UUID createdBy, String createdByName, Instant completedAt, String completionNote,
                           Instant createdAt, Instant updatedAt, boolean overdue) {
    }

    public record CreateBody(@NotBlank @Size(max = 160) String title, @Size(max = 1000) String description,
                             @NotNull UUID assigneeId, Priority priority, Instant dueAt, UUID machineId) {
    }

    public record UpdateBody(@Size(max = 160) String title, @Size(max = 1000) String description, UUID assigneeId,
                             Priority priority, Instant dueAt, Boolean clearDueAt, UUID machineId,
                             Boolean clearMachine) {
    }

    public record StatusBody(@NotNull Status status, @Size(max = 500) String note) {
    }

    private static final String SELECT = """
            select t.id, t.title, t.description, t.assignee_id, a.full_name, t.priority, t.status, t.due_at,
                   t.machine_id, m.code, t.created_by, c.full_name, t.completed_at, t.completion_note,
                   t.created_at, t.updated_at
            from staff_tasks t join users a on a.id = t.assignee_id join users c on c.id = t.created_by
            left join machines m on m.id = t.machine_id
            """;

    private RowMapper<TaskView> row(Instant now) {
        return (rs, i) -> {
            Timestamp due = rs.getTimestamp(8);
            Status status = Status.valueOf(rs.getString(7));
            boolean open = status == Status.TODO || status == Status.IN_PROGRESS;
            return new TaskView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getObject(4, UUID.class), rs.getString(5), Priority.valueOf(rs.getString(6)), status,
                    due == null ? null : due.toInstant(), rs.getObject(9, UUID.class), rs.getString(10),
                    rs.getObject(11, UUID.class), rs.getString(12),
                    rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant(), rs.getString(14),
                    rs.getTimestamp(15).toInstant(), rs.getTimestamp(16).toInstant(),
                    open && due != null && due.toInstant().isBefore(now));
        };
    }

    private static final String ORDER = """
             order by case t.status when 'IN_PROGRESS' then 0 when 'TODO' then 1 when 'DONE' then 2 else 3 end,
                      case t.priority when 'HIGH' then 0 when 'NORMAL' then 1 else 2 end,
                      t.due_at nulls last, t.created_at desc limit 500
            """;

    @GetMapping("/api/owner/tasks")
    @Operation(summary = "Mọi nhiệm vụ đã giao; lọc theo trạng thái và nhân viên")
    @Transactional(readOnly = true)
    public List<TaskView> list(@RequestParam(required = false) Set<Status> status,
                               @RequestParam(required = false) UUID assigneeId) {
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            where.append(" and t.status in (").append(String.join(",", status.stream().map(s -> "?").toList())).append(")");
            status.forEach(s -> args.add(s.name()));
        }
        if (assigneeId != null) {
            where.append(" and t.assignee_id = ?");
            args.add(assigneeId);
        }
        return jdbc.query(SELECT + where + ORDER, row(clock.instant()), args.toArray());
    }

    @GetMapping("/api/staff/tasks")
    @Operation(summary = "Nhiệm vụ được giao cho tôi; mặc định chỉ việc chưa xong và việc xong trong 7 ngày")
    @Transactional(readOnly = true)
    public List<TaskView> mine(@RequestParam(defaultValue = "false") boolean all) {
        Instant now = clock.instant();
        String filter = all ? "" : " and (t.status in ('TODO', 'IN_PROGRESS') or t.updated_at >= ?)";
        List<Object> args = new ArrayList<>(List.of(Actor.userId()));
        if (!all) args.add(Timestamp.from(now.minusSeconds(7 * 86_400)));
        return jdbc.query(SELECT + " where t.assignee_id = ?" + filter + ORDER, row(now), args.toArray());
    }

    @PostMapping("/api/owner/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TaskView create(@Valid @RequestBody CreateBody body) {
        users.requireStaff(body.assigneeId());
        requireMachine(body.machineId());
        UUID id = UUID.randomUUID();
        Priority priority = body.priority() == null ? Priority.NORMAL : body.priority();
        jdbc.update("""
                insert into staff_tasks (id, title, description, assignee_id, priority, due_at, machine_id, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, body.title().trim(), blank(body.description()), body.assigneeId(), priority.name(),
                body.dueAt() == null ? null : Timestamp.from(body.dueAt()), body.machineId(), Actor.userId());
        audit.user("TASK_ASSIGNED", "staff_task", id.toString(), "Giao việc: " + body.title().trim(), null,
                Map.of("assigneeId", body.assigneeId(), "priority", priority));
        return load(id);
    }

    @PutMapping("/api/owner/tasks/{id}")
    @Transactional
    public TaskView update(@PathVariable UUID id, @Valid @RequestBody UpdateBody body) {
        TaskView t = lock(id);
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (body.title() != null && !body.title().isBlank() && !body.title().trim().equals(t.title())) {
            change(before, after, "title", t.title(), body.title().trim());
            jdbc.update("update staff_tasks set title = ? where id = ?", body.title().trim(), id);
        }
        if (body.description() != null && !Objects.equals(blank(body.description()), t.description())) {
            change(before, after, "description", t.description(), blank(body.description()));
            jdbc.update("update staff_tasks set description = ? where id = ?", blank(body.description()), id);
        }
        if (body.assigneeId() != null && !body.assigneeId().equals(t.assigneeId())) {
            users.requireStaff(body.assigneeId());
            change(before, after, "assigneeId", t.assigneeId(), body.assigneeId());
            jdbc.update("update staff_tasks set assignee_id = ? where id = ?", body.assigneeId(), id);
        }
        if (body.priority() != null && body.priority() != t.priority()) {
            change(before, after, "priority", t.priority(), body.priority());
            jdbc.update("update staff_tasks set priority = ? where id = ?", body.priority().name(), id);
        }
        Instant due = Boolean.TRUE.equals(body.clearDueAt()) ? null : body.dueAt() != null ? body.dueAt() : t.dueAt();
        if (!Objects.equals(due, t.dueAt())) {
            change(before, after, "dueAt", t.dueAt(), due);
            jdbc.update("update staff_tasks set due_at = ? where id = ?", due == null ? null : Timestamp.from(due), id);
        }
        UUID machine = Boolean.TRUE.equals(body.clearMachine()) ? null : body.machineId() != null ? body.machineId() : t.machineId();
        if (!Objects.equals(machine, t.machineId())) {
            requireMachine(machine);
            change(before, after, "machineId", t.machineId(), machine);
            jdbc.update("update staff_tasks set machine_id = ? where id = ?", machine, id);
        }
        if (after.isEmpty()) return t;
        touch(id);
        audit.user("TASK_UPDATED", "staff_task", id.toString(), "Sửa việc: " + t.title(), before, after);
        return load(id);
    }

    /**
     * Nhân viên chuyển việc của mình: TODO → IN_PROGRESS → DONE (hoặc mở lại). Chủ cửa hàng chuyển được mọi việc,
     * kể cả hủy (CANCELLED).
     */
    @PutMapping("/api/staff/tasks/{id}/status")
    @Transactional
    public TaskView status(@PathVariable UUID id, @Valid @RequestBody StatusBody body) {
        TaskView t = lock(id);
        Actor.User me = Actor.user();
        boolean owner = me.has(Role.OWNER);
        if (!owner && !t.assigneeId().equals(me.id())) throw new BusinessException(ErrorCode.FORBIDDEN, "Đây không phải việc được giao cho bạn");
        if (body.status() == Status.CANCELLED && !owner) throw new BusinessException(ErrorCode.FORBIDDEN, "Chỉ Chủ cửa hàng hủy việc");
        if (t.status() == Status.CANCELLED && !owner) throw new BusinessException(ErrorCode.ORDER_STATE, "Việc đã bị hủy");
        if (body.status() == t.status()) return t;
        boolean done = body.status() == Status.DONE;
        Instant now = clock.instant();
        jdbc.update("update staff_tasks set status = ?, completed_at = ?, completion_note = ? where id = ?",
                body.status().name(), done ? Timestamp.from(now) : null, done ? blank(body.note()) : t.completionNote(), id);
        touch(id);
        audit.user("TASK_STATUS_CHANGED", "staff_task", id.toString(),
                blank(body.note()) != null ? body.note().trim() : "Cập nhật tiến độ: " + t.title(),
                Map.of("status", t.status()), Map.of("status", body.status()));
        return load(id);
    }

    private TaskView load(UUID id) {
        return jdbc.query(SELECT + " where t.id = ?", row(clock.instant()), id).stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("nhiệm vụ"));
    }

    private TaskView lock(UUID id) {
        jdbc.query("select id from staff_tasks where id = ? for update", rs -> { }, id);
        return load(id);
    }

    private void touch(UUID id) {
        jdbc.update("update staff_tasks set updated_at = ?, version = version + 1 where id = ?", Timestamp.from(clock.instant()), id);
    }

    private void requireMachine(UUID machineId) {
        if (machineId == null) return;
        Integer n = jdbc.queryForObject("select count(*) from machines where id = ?", Integer.class, machineId);
        if (n == null || n == 0) throw BusinessException.notFound("máy");
    }

    private static void change(Map<String, Object> before, Map<String, Object> after, String key, Object from, Object to) {
        before.put(key, from);
        after.put(key, to);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
