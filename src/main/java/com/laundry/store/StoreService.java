package com.laundry.store;

import com.laundry.audit.AuditService;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.security.Actor;
import com.laundry.store.StoreDtos.ClosureView;
import com.laundry.store.StoreDtos.HourSlot;
import com.laundry.store.StoreDtos.HourView;
import com.laundry.store.StoreDtos.Readiness;
import com.laundry.store.StoreDtos.ReadinessCheck;
import com.laundry.store.StoreDtos.UpdateSettings;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StoreService {

    private final StoreSettingsRepository settingsRepository;
    private final StoreHourRepository hours;
    private final StoreClosureRepository closures;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public StoreSettings settings() {
        return settingsRepository.findById(1).orElseThrow(() -> new IllegalStateException("store_settings row missing"));
    }

    @Transactional(readOnly = true)
    public StoreCalendar calendar() {
        StoreSettings s = settings();
        List<StoreHour> all = hours.findAll();
        List<StoreCalendar.Closure> cs = closures.upcoming(clock.instant().minusSeconds(86_400)).stream()
                .map(c -> new StoreCalendar.Closure(c.getStartsAt(), c.getEndsAt())).toList();
        return new StoreCalendar(s.zone(), all, cs);
    }

    // ---- Tham số ----

    /**
     * Đổi tham số không ảnh hưởng đơn đã xác nhận (§16): mỗi đơn đã chụp tham số lúc đặt (orders.policy_snapshot).
     */
    /** Ai đổi tham số: Chủ cửa hàng đổi vận hành, dịch vụ, giá; Quản trị viên đổi chính sách hệ thống. */
    public enum SettingsScope { OWNER, ADMIN }

    @Transactional
    public StoreSettings update(UpdateSettings body) {
        return update(body, SettingsScope.OWNER);
    }

    /**
     * Đổi tham số không ảnh hưởng đơn đã xác nhận (§16). Trường chính sách ({@link StoreDtos#POLICY_FIELDS}) chỉ
     * Quản trị viên đổi; Quản trị viên chỉ đổi trường chính sách.
     */
    @Transactional
    public StoreSettings update(UpdateSettings body, SettingsScope scope) {
        String reason = Reasons.require(body.reason());
        StoreSettings s = settings();
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        try {
            for (RecordComponent c : UpdateSettings.class.getRecordComponents()) {
                if (c.getName().equals("reason")) continue;
                Object value = c.getAccessor().invoke(body);
                if (value == null) continue;
                var field = StoreSettings.class.getDeclaredField(c.getName());
                field.setAccessible(true);
                Object old = field.get(s);
                if (value instanceof String str) value = str.trim();
                boolean same = value instanceof BigDecimal d && old instanceof BigDecimal o ? d.compareTo(o) == 0 : value.equals(old);
                // Gửi lại đúng giá trị đang lưu thì bỏ qua; chỉ chặn khi thật sự đổi trường ngoài quyền.
                boolean policy = StoreDtos.POLICY_FIELDS.contains(c.getName());
                if (!same && policy && scope == SettingsScope.OWNER) {
                    throw new BusinessException(ErrorCode.FORBIDDEN,
                            "Quy định đặt lịch, hủy đơn, hoàn tiền là chính sách hệ thống: Quản trị viên chỉnh");
                }
                if (!same && !policy && scope == SettingsScope.ADMIN) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST,
                            "Trang Chính sách hệ thống chỉ đổi quy định đặt lịch, hủy đơn, hoàn tiền");
                }
                // Tắt một dịch vụ: còn khách đang dùng thì từ chối; chỉ còn đơn đặt sắp tới thì cho tắt (đơn đó vẫn phục vụ).
                String service = SERVICE_FLAGS.get(c.getName());
                if (!same && service != null && Boolean.FALSE.equals(value) && Boolean.TRUE.equals(old)) {
                    StoreDtos.ServiceImpact impact = serviceImpact(service);
                    if (!impact.inUse().isEmpty()) {
                        throw new BusinessException(ErrorCode.SERVICE_IN_USE, "Đang có " + impact.inUse().size()
                                + " khách dùng dịch vụ này (" + String.join(", ", impact.inUse().stream().limit(10)
                                .map(StoreDtos.ImpactItem::code).toList()) + "); chờ xong hoặc xử lý các đơn đó rồi tắt",
                                Map.of("orders", impact.inUse().stream().map(StoreDtos.ImpactItem::code).toList()));
                    }
                }
                if (!same) {
                    before.put(c.getName(), old);
                    after.put(c.getName(), value);
                    field.set(s, value);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("UpdateSettings does not match StoreSettings", e);
        }
        if (after.isEmpty()) return s;
        if (s.getStartWindowMinutes() > s.getGraceMinutes() + 30) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Thời hạn bấm START quá dài");
        }
        s.setUpdatedBy(Actor.userId());
        boolean priceRelated = after.keySet().stream().anyMatch(k -> k.startsWith("deposit") || k.startsWith("laundry")
                || k.endsWith("Vnd") || k.endsWith("PerDay") || k.endsWith("PerKg"));
        String action = scope == SettingsScope.ADMIN ? "SYSTEM_POLICY_CHANGED"
                : priceRelated ? "STORE_PRICING_CHANGED" : "STORE_SETTINGS_CHANGED";
        audit.user(action, "store_settings", "1", reason, before, after);
        return s;
    }

    /** Cờ bật/tắt trong store_settings → mã dịch vụ (orders.service hoặc service_jobs.service). */
    static final Map<String, String> SERVICE_FLAGS = Map.of(
            "washEnabled", "WASH", "dryEnabled", "DRY", "washDryEnabled", "WASH_DRY",
            "laundryServiceEnabled", "LAUNDRY", "ironingEnabled", "IRON", "washDryIronEnabled", "WASH_DRY_IRON");

    /**
     * Ảnh hưởng nếu tắt một dịch vụ. inUse: khách đang dùng ngay lúc này (máy đã kích hoạt / đang chạy / chờ lấy đồ;
     * đồ tại quầy đã nhận hoặc đang làm) — có thì không cho tắt. upcoming: đơn đã đặt chưa tới giờ, đồ tại quầy đã xong
     * chờ khách lấy — vẫn được phục vụ sau khi tắt, chỉ để cảnh báo.
     */
    @Transactional(readOnly = true)
    public StoreDtos.ServiceImpact serviceImpact(String service) {
        if (!SERVICE_FLAGS.containsValue(service)) throw new BusinessException(ErrorCode.BAD_REQUEST, "Dịch vụ không hợp lệ");
        // Đơn bắt đầu trong 1 giờ qua vẫn có thể đang trong ân hạn check-in: tính là sắp tới.
        java.sql.Timestamp now = java.sql.Timestamp.from(clock.instant().minusSeconds(3600));
        List<StoreDtos.ImpactItem> inUse;
        List<StoreDtos.ImpactItem> upcoming;
        if (service.equals("IRON") || service.equals("WASH_DRY_IRON")) {
            inUse = jdbc.query("""
                    select code, status, due_at from service_jobs
                    where service = ? and status in ('RECEIVED', 'PROCESSING') order by due_at
                    """, (rs, i) -> new StoreDtos.ImpactItem(rs.getString(1),
                    rs.getString(2).equals("RECEIVED") ? "Đã nhận đồ" : "Đang làm", rs.getTimestamp(3).toInstant()), service);
            upcoming = jdbc.query("""
                    select code, due_at from service_jobs where service = ? and status = 'READY' order by due_at
                    """, (rs, i) -> new StoreDtos.ImpactItem(rs.getString(1), "Xong, chờ khách lấy",
                    rs.getTimestamp(2).toInstant()), service);
        } else {
            inUse = jdbc.query("""
                    select o.code, min(l.status), o.scheduled_start_at from orders o join order_legs l on l.order_id = o.id
                    where o.service = ? and o.status in ('CONFIRMED', 'IN_PROGRESS')
                      and l.status in ('ACTIVATED', 'RUNNING', 'AWAITING_UNLOAD')
                    group by o.code, o.scheduled_start_at order by o.scheduled_start_at
                    """, (rs, i) -> new StoreDtos.ImpactItem(rs.getString(1), switch (rs.getString(2)) {
                case "ACTIVATED" -> "Chờ bấm START";
                case "RUNNING" -> "Máy đang chạy";
                default -> "Chờ lấy đồ";
            }, rs.getTimestamp(3).toInstant()), service);
            java.util.Set<String> busy = new java.util.HashSet<>(inUse.stream().map(StoreDtos.ImpactItem::code).toList());
            upcoming = jdbc.query("""
                    select code, status, scheduled_start_at from orders
                    where service = ? and status in ('HOLD', 'CONFIRMED', 'IN_PROGRESS') and scheduled_start_at >= ?
                    order by scheduled_start_at limit 500
                    """, (rs, i) -> new StoreDtos.ImpactItem(rs.getString(1),
                    rs.getString(2).equals("HOLD") ? "Đang thanh toán" : "Đã đặt", rs.getTimestamp(3).toInstant()), service, now)
                    .stream().filter(x -> !busy.contains(x.code())).toList();
        }
        return new StoreDtos.ServiceImpact(service, inUse, upcoming);
    }

    // ---- Giờ mở cửa ----

    public List<HourView> hours(StoreHour.Kind kind) {
        return hours.findByKindOrderByDayOfWeekAscOpensAtAsc(kind).stream().map(HourView::of).toList();
    }

    /** Thay toàn bộ khung giờ của một loại. Giờ mở cửa mới làm đơn đã đặt không chạy được thì từ chối (§16). */
    @Transactional
    public List<HourView> replaceHours(StoreHour.Kind kind, List<HourSlot> slots, String rawReason) {
        String reason = Reasons.require(rawReason);
        validateSlots(slots);
        List<HourView> before = hours(kind);
        hours.deleteByKind(kind);
        hours.flush();
        List<StoreHour> created = slots.stream().map(slot -> {
            StoreHour h = new StoreHour();
            h.setKind(kind);
            h.setDayOfWeek(slot.dayOfWeek());
            h.setOpensAt(slot.opensAt());
            h.setClosesAt(slot.closesAt());
            return h;
        }).toList();
        hours.saveAllAndFlush(created);
        if (kind == StoreHour.Kind.OPEN) {
            List<String> broken = ordersOutside(calendar());
            if (!broken.isEmpty()) {
                throw new BusinessException(ErrorCode.CONFIG_BREAKS_ORDERS,
                        "Giờ mở cửa mới làm " + broken.size() + " đơn đã đặt không chạy được; xử lý các đơn đó trước",
                        Map.of("orders", broken));
            }
        }
        audit.user("STORE_HOURS_CHANGED", "store_hours", kind.name(), reason, before,
                created.stream().map(HourView::of).toList());
        return hours(kind);
    }

    public List<ClosureView> upcomingClosures() {
        return closures.upcoming(clock.instant()).stream().map(ClosureView::of).toList();
    }

    @Transactional
    public ClosureView addClosure(Instant startsAt, Instant endsAt, String reason) {
        if (!endsAt.isAfter(startsAt)) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Giờ kết thúc phải sau giờ bắt đầu");
        StoreClosure c = new StoreClosure();
        c.setStartsAt(startsAt);
        c.setEndsAt(endsAt);
        c.setReason(Reasons.require(reason));
        c.setCreatedBy(Actor.userId());
        closures.saveAndFlush(c);
        List<String> broken = ordersOutside(calendar());
        if (!broken.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFIG_BREAKS_ORDERS,
                    "Ngày nghỉ trùng " + broken.size() + " đơn đã đặt; xử lý các đơn đó trước", Map.of("orders", broken));
        }
        audit.user("STORE_CLOSURE_ADDED", "store_closure", c.getId().toString(), c.getReason(), null,
                Map.of("startsAt", startsAt.toString(), "endsAt", endsAt.toString()));
        return ClosureView.of(c);
    }

    @Transactional
    public void removeClosure(UUID id, String reason) {
        StoreClosure c = closures.findById(id).orElseThrow(() -> BusinessException.notFound("ngày nghỉ"));
        closures.delete(c);
        audit.user("STORE_CLOSURE_REMOVED", "store_closure", id.toString(), Reasons.require(reason),
                Map.of("startsAt", c.getStartsAt().toString(), "endsAt", c.getEndsAt().toString()), null);
    }

    // ---- Sẵn sàng (§16) ----

    @Transactional(readOnly = true)
    public Readiness readiness() {
        StoreSettings s = settings();
        List<ReadinessCheck> checks = new ArrayList<>();
        boolean hasHours = calendar().hasOpeningHours();
        checks.add(new ReadinessCheck("OPENING_HOURS", hasHours, hasHours ? "Đã có giờ mở cửa" : "Chưa đặt giờ mở cửa"));
        boolean service = s.isWashEnabled() || s.isDryEnabled() || s.isWashDryEnabled() || s.isLaundryServiceEnabled();
        checks.add(new ReadinessCheck("SERVICE_ENABLED", service, service ? "Có dịch vụ đang bật" : "Chưa bật dịch vụ nào"));
        Integer priced = jdbc.queryForObject("select count(*) from machines where admin_status = 'ACTIVE' and price_vnd is not null",
                Integer.class);
        checks.add(new ReadinessCheck("PRICED_MACHINE", priced != null && priced > 0,
                priced != null && priced > 0 ? priced + " máy đã có giá" : "Chưa có máy nào có giá"));
        Integer online = jdbc.queryForObject("""
                select count(*) from machines m join devices d on d.machine_id = m.id
                where m.admin_status = 'ACTIVE' and d.link_state = 'BOUND' and d.online
                """, Integer.class);
        checks.add(new ReadinessCheck("ONLINE_MACHINE", online != null && online > 0,
                online != null && online > 0 ? online + " máy đang online" : "Chưa có máy nào online"));
        return new Readiness(checks.stream().allMatch(ReadinessCheck::ok), checks);
    }

    public void requireReady() {
        Readiness r = readiness();
        if (!r.ready()) {
            throw new BusinessException(ErrorCode.STORE_NOT_READY, ErrorCode.STORE_NOT_READY.message(),
                    Map.of("checks", r.checks().stream().filter(c -> !c.ok()).map(ReadinessCheck::message).toList()));
        }
    }

    public StoreDtos.PublicStore publicView() {
        StoreSettings s = settings();
        StoreCalendar cal = calendar();
        return new StoreDtos.PublicStore(s.getName(), s.getAddress(), s.getPhone(), s.getTimezone(),
                s.isWashEnabled(), s.isDryEnabled(), s.isWashDryEnabled(), s.isLaundryServiceEnabled(),
                readiness().ready(), cal.isOpen(clock.instant()), hours(StoreHour.Kind.OPEN), upcomingClosures(),
                s.getMaxAdvanceBookingHours(), s.getDepositPercent(), s.getGraceMinutes(), s.getEarlyCheckinMinutes(),
                s.getStartWindowMinutes(), s.getFreeCancelBeforeMinutes(), s.getFreeCancelAfterBookingMinutes(),
                s.getKioskMaxWaitMinutes(), s.isIroningEnabled(), s.isWashDryIronEnabled(),
                s.getIroningPricePerKg(), s.getWashDryIronPricePerKg());
    }

    /**
     * Đơn đang mở mà khoảng giữ máy (tới hết phần lấy đồ) không còn nằm trong giờ mở cửa.
     * Khoảng giữ máy kết thúc bằng phần dọn máy; phần dọn được phép sau giờ đóng cửa.
     */
    private List<String> ordersOutside(StoreCalendar cal) {
        Instant now = clock.instant();
        List<Object[]> rows = jdbc.query("""
                select o.code, r.starts_at, r.ends_at, coalesce((o.policy_snapshot->>'cleanupMinutes')::int, 0)
                from machine_reservations r
                join order_legs l on l.reservation_id = r.id
                join orders o on o.id = l.order_id
                where r.active and r.ends_at > ? and o.status in ('HOLD', 'CONFIRMED', 'IN_PROGRESS')
                  and l.status in ('SCHEDULED', 'ACTIVATED')
                """, (rs, i) -> new Object[]{rs.getString(1), rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3).toInstant(), rs.getInt(4)}, java.sql.Timestamp.from(now));
        return rows.stream()
                .filter(r -> !cal.fitsOpen((Instant) r[1], ((Instant) r[2]).minusSeconds(60L * (int) r[3])))
                .map(r -> (String) r[0]).distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private static void validateSlots(List<HourSlot> slots) {
        for (HourSlot slot : slots) {
            if (!slot.closesAt().isAfter(slot.opensAt())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Giờ đóng cửa phải sau giờ mở cửa (không qua đêm)");
            }
        }
        for (int i = 0; i < slots.size(); i++) {
            for (int j = i + 1; j < slots.size(); j++) {
                HourSlot a = slots.get(i), b = slots.get(j);
                if (a.dayOfWeek() == b.dayOfWeek() && a.opensAt().isBefore(b.closesAt()) && b.opensAt().isBefore(a.closesAt())) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Các khung giờ trong cùng một ngày bị chồng nhau");
                }
            }
        }
    }
}
