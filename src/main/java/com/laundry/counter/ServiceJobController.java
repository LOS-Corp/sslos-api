package com.laundry.counter;

import com.laundry.audit.AuditService;
import com.laundry.common.Phones;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.security.Actor;
import com.laundry.store.StoreService;
import com.laundry.store.StoreSettings;
import com.laundry.user.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dịch vụ tại quầy do nhân viên làm: ủi (IRON) và giặt-sấy-ủi (WASH_DRY_IRON). Khách mang đồ tới quầy, nhân viên cân
 * và báo giá theo kg (làm tròn lên 0,5 kg, không dưới mức tối thiểu), thu tiền mặt hoặc chuyển khoản, làm xong thì
 * báo khách tới lấy. Bật/tắt và giá do Chủ cửa hàng chỉnh trong tham số cửa hàng.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Nhân viên · Dịch vụ tại quầy")
public class ServiceJobController {

    public enum Service { IRON, WASH_DRY_IRON }

    public enum Status { RECEIVED, PROCESSING, READY, PICKED_UP, CANCELLED }

    public enum PaymentMethod { CASH, TRANSFER }

    private static final BigDecimal STEP = new BigDecimal("0.5");

    private final JdbcTemplate jdbc;
    private final StoreService store;
    private final UserService users;
    private final AuditService audit;
    private final Clock clock;

    public record JobView(UUID id, String code, Service service, String customerName, String customerPhone,
                          BigDecimal weightKg, BigDecimal billableWeightKg, long pricePerKg, long amount,
                          PaymentMethod paymentMethod, Instant paidAt, Status status, String note, Instant dueAt,
                          UUID receivedBy, String receivedByName, UUID assigneeId, String assigneeName,
                          Instant readyAt, Instant pickedUpAt, Instant cancelledAt, String cancelReason,
                          Instant createdAt, boolean overdue) {
    }

    public record Quote(Service service, BigDecimal weightKg, BigDecimal billableWeightKg, long pricePerKg, long amount,
                        Instant dueAt) {
    }

    public record CreateBody(@NotNull Service service, @NotBlank @Size(max = 120) String customerName,
                             @NotBlank String customerPhone,
                             @NotNull @DecimalMin("0.1") @DecimalMax("200") BigDecimal weightKg,
                             @Size(max = 500) String note, PaymentMethod paidWith, UUID assigneeId) {
    }

    public record PayBody(@NotNull PaymentMethod method) {
    }

    public record StatusBody(@NotNull Status status, UUID assigneeId) {
    }

    public record CancelBody(@NotBlank @Size(max = 255) String reason) {
    }

    private static final String SELECT = """
            select j.id, j.code, j.service, j.customer_name, j.customer_phone, j.weight_kg, j.billable_weight_kg,
                   j.price_per_kg, j.amount, j.payment_method, j.paid_at, j.status, j.note, j.due_at, j.received_by,
                   r.full_name, j.assignee_id, a.full_name, j.ready_at, j.picked_up_at, j.cancelled_at, j.cancel_reason,
                   j.created_at
            from service_jobs j join users r on r.id = j.received_by left join users a on a.id = j.assignee_id
            """;

    private RowMapper<JobView> row(Instant now) {
        return (rs, i) -> {
            Status status = Status.valueOf(rs.getString(12));
            Instant due = rs.getTimestamp(14).toInstant();
            String method = rs.getString(10);
            return new JobView(rs.getObject(1, UUID.class), rs.getString(2), Service.valueOf(rs.getString(3)),
                    rs.getString(4), rs.getString(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getLong(8),
                    rs.getLong(9), method == null ? null : PaymentMethod.valueOf(method), ts(rs.getTimestamp(11)), status,
                    rs.getString(13), due, rs.getObject(15, UUID.class), rs.getString(16), rs.getObject(17, UUID.class),
                    rs.getString(18), ts(rs.getTimestamp(19)), ts(rs.getTimestamp(20)), ts(rs.getTimestamp(21)),
                    rs.getString(22), rs.getTimestamp(23).toInstant(),
                    (status == Status.RECEIVED || status == Status.PROCESSING) && due.isBefore(now));
        };
    }

    @GetMapping("/api/staff/service-jobs")
    @Operation(summary = "Đơn dịch vụ tại quầy; mặc định đơn đang làm và đơn đã xong trong 3 ngày; q tìm mã/tên/SĐT")
    @Transactional(readOnly = true)
    public List<JobView> list(@RequestParam(required = false) Set<Status> status,
                              @RequestParam(required = false) String q) {
        Instant now = clock.instant();
        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isEmpty()) {
            where.append(" and j.status in (").append(String.join(",", status.stream().map(s -> "?").toList())).append(")");
            status.forEach(s -> args.add(s.name()));
        } else if (q == null || q.isBlank()) {
            where.append(" and (j.status in ('RECEIVED', 'PROCESSING', 'READY') or j.updated_at >= ?)");
            args.add(Timestamp.from(now.minus(Duration.ofDays(3))));
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            // SĐT lưu dạng +84907...: bỏ số 0 đầu (0907...) và mã 84 để khớp phần còn lại.
            String digits = q.replaceAll("\\D", "").replaceFirst("^(84|0)", "");
            where.append(" and (lower(j.code) like ? or lower(j.customer_name) like ?")
                    .append(digits.length() >= 3 ? " or j.customer_phone like ?)" : ")");
            args.add(like);
            args.add(like);
            if (digits.length() >= 3) args.add("%" + digits + "%");
        }
        return jdbc.query(SELECT + where + """
                 order by case j.status when 'READY' then 0 when 'PROCESSING' then 1 when 'RECEIVED' then 2 else 3 end,
                          j.due_at, j.created_at desc limit 500
                """, row(now), args.toArray());
    }

    @GetMapping("/api/staff/service-jobs/quote")
    @Operation(summary = "Báo giá trước khi nhận đồ: số kg tính tiền, đơn giá, thành tiền, hẹn trả")
    public Quote quote(@RequestParam Service service, @RequestParam BigDecimal weightKg) {
        StoreSettings s = store.settings();
        requireEnabled(s, service);
        return quote(s, service, weightKg, clock.instant());
    }

    @PostMapping("/api/staff/service-jobs")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public JobView create(@Valid @RequestBody CreateBody body) {
        StoreSettings s = store.settings();
        requireEnabled(s, body.service());
        if (body.assigneeId() != null) users.requireStaff(body.assigneeId());
        Instant now = clock.instant();
        Quote q = quote(s, body.service(), body.weightKg(), now);
        String phone = Phones.normalize(body.customerPhone());
        Long seq = jdbc.queryForObject("select nextval('service_job_code_seq')", Long.class);
        String code = String.format("SV%05d", seq);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into service_jobs (id, code, service, customer_name, customer_phone, weight_kg, billable_weight_kg,
                                          price_per_kg, amount, payment_method, paid_at, note, due_at, received_by, assignee_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, code, body.service().name(), body.customerName().trim(), phone, body.weightKg(),
                q.billableWeightKg(), q.pricePerKg(), q.amount(),
                body.paidWith() == null ? null : body.paidWith().name(), body.paidWith() == null ? null : Timestamp.from(now),
                blank(body.note()), Timestamp.from(q.dueAt()), Actor.userId(), body.assigneeId());
        audit.user("SERVICE_JOB_CREATED", "service_job", id.toString(), "Nhận đồ " + code, null,
                Map.of("service", body.service(), "weightKg", body.weightKg(), "amount", q.amount(),
                        "paid", body.paidWith() != null));
        return load(id);
    }

    @PostMapping("/api/staff/service-jobs/{id}/pay")
    @Operation(summary = "Ghi nhận khách đã trả (tiền mặt / chuyển khoản)")
    @Transactional
    public JobView pay(@PathVariable UUID id, @Valid @RequestBody PayBody body) {
        JobView j = lock(id);
        if (j.status() == Status.CANCELLED) throw new BusinessException(ErrorCode.ORDER_STATE, "Đơn đã hủy");
        if (j.paidAt() != null) throw new BusinessException(ErrorCode.ORDER_STATE, "Đơn đã được trả");
        jdbc.update("update service_jobs set payment_method = ?, paid_at = ? where id = ?", body.method().name(),
                Timestamp.from(clock.instant()), id);
        touch(id);
        audit.user("SERVICE_JOB_PAID", "service_job", id.toString(), "Thu tiền " + j.code(), null,
                Map.of("amount", j.amount(), "method", body.method()));
        return load(id);
    }

    /** RECEIVED → PROCESSING → READY → PICKED_UP (phải trả đủ trước khi giao đồ). */
    @PutMapping("/api/staff/service-jobs/{id}/status")
    @Transactional
    public JobView status(@PathVariable UUID id, @Valid @RequestBody StatusBody body) {
        JobView j = lock(id);
        Status to = body.status();
        boolean ok = switch (j.status()) {
            case RECEIVED -> to == Status.PROCESSING || to == Status.READY;
            case PROCESSING -> to == Status.READY || to == Status.RECEIVED;
            case READY -> to == Status.PICKED_UP || to == Status.PROCESSING;
            default -> false;
        };
        if (!ok) throw new BusinessException(ErrorCode.ORDER_STATE, "Không chuyển được đơn từ " + j.status() + " sang " + to);
        if (to == Status.PICKED_UP && j.paidAt() == null) {
            throw new BusinessException(ErrorCode.ORDER_STATE, "Khách chưa trả tiền: ghi nhận thanh toán trước khi giao đồ");
        }
        UUID assignee = body.assigneeId() != null ? body.assigneeId()
                : j.assigneeId() != null ? j.assigneeId() : to == Status.PROCESSING ? Actor.userId() : null;
        if (body.assigneeId() != null) users.requireStaff(body.assigneeId());
        Instant now = clock.instant();
        jdbc.update("""
                update service_jobs set status = ?, assignee_id = ?,
                       ready_at = case when ? = 'READY' then ? else ready_at end,
                       picked_up_at = case when ? = 'PICKED_UP' then ? else picked_up_at end
                where id = ?
                """, to.name(), assignee, to.name(), Timestamp.from(now), to.name(), Timestamp.from(now), id);
        touch(id);
        audit.user("SERVICE_JOB_STATUS_CHANGED", "service_job", id.toString(), "Cập nhật đơn " + j.code(),
                Map.of("status", j.status()), Map.of("status", to));
        return load(id);
    }

    /** Hủy đơn chưa giao. Đơn đã thu tiền: nhân viên trả lại tiền tại quầy, báo cáo không tính doanh thu đơn này. */
    @PostMapping("/api/staff/service-jobs/{id}/cancel")
    @Transactional
    public JobView cancel(@PathVariable UUID id, @Valid @RequestBody CancelBody body) {
        String reason = Reasons.require(body.reason());
        JobView j = lock(id);
        if (j.status() == Status.PICKED_UP || j.status() == Status.CANCELLED) {
            throw new BusinessException(ErrorCode.ORDER_STATE, "Đơn đã giao hoặc đã hủy");
        }
        jdbc.update("update service_jobs set status = 'CANCELLED', cancelled_at = ?, cancel_reason = ? where id = ?",
                Timestamp.from(clock.instant()), reason, id);
        touch(id);
        audit.user("SERVICE_JOB_CANCELLED", "service_job", id.toString(), reason, Map.of("status", j.status()),
                Map.of("status", Status.CANCELLED, "refundAtCounterVnd", j.paidAt() == null ? 0 : j.amount()));
        return load(id);
    }

    private static Quote quote(StoreSettings s, Service service, BigDecimal weightKg, Instant now) {
        if (weightKg == null || weightKg.signum() <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Cân nặng phải lớn hơn 0");
        BigDecimal rounded = weightKg.divide(STEP, 0, RoundingMode.CEILING).multiply(STEP).setScale(1, RoundingMode.UNNECESSARY);
        BigDecimal billable = rounded.max(s.getCounterMinWeightKg().setScale(1, RoundingMode.CEILING));
        long price = service == Service.IRON ? s.getIroningPricePerKg() : s.getWashDryIronPricePerKg();
        long amount = billable.multiply(BigDecimal.valueOf(price)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return new Quote(service, weightKg, billable, price, amount, now.plus(Duration.ofHours(s.getCounterTurnaroundHours())));
    }

    private static void requireEnabled(StoreSettings s, Service service) {
        boolean on = service == Service.IRON ? s.isIroningEnabled() : s.isWashDryIronEnabled();
        if (!on) throw new BusinessException(ErrorCode.SERVICE_DISABLED,
                (service == Service.IRON ? "Dịch vụ ủi" : "Dịch vụ giặt-sấy-ủi") + " đang tắt");
    }

    private JobView load(UUID id) {
        return jdbc.query(SELECT + " where j.id = ?", row(clock.instant()), id).stream().findFirst()
                .orElseThrow(() -> BusinessException.notFound("đơn dịch vụ"));
    }

    private JobView lock(UUID id) {
        jdbc.query("select id from service_jobs where id = ? for update", rs -> { }, id);
        return load(id);
    }

    private void touch(UUID id) {
        jdbc.update("update service_jobs set updated_at = ?, version = version + 1 where id = ?", Timestamp.from(clock.instant()), id);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
