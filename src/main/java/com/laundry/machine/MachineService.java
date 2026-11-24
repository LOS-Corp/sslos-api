package com.laundry.machine;

import com.laundry.audit.AuditService;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.machine.MachineDtos.CreateMachine;
import com.laundry.machine.MachineDtos.MachineView;
import com.laundry.machine.MachineDtos.MaintenanceView;
import com.laundry.machine.MachineDtos.PackView;
import com.laundry.machine.MachineDtos.UpdateMachine;
import com.laundry.security.Actor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MachineService {

    private final MachineRepository machines;
    private final PriceProfileRepository profiles;
    private final DryerTimePackRepository packs;
    private final MachineReservationRepository reservations;
    private final MachineStatusResolver statusResolver;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public Machine get(UUID id) {
        return machines.findById(id).orElseThrow(() -> BusinessException.notFound("máy"));
    }

    @Transactional(readOnly = true)
    public List<MachineView> list(boolean includeRetired) {
        List<Machine> all = includeRetired ? machines.listAll() : machines.listInService();
        Map<UUID, MachineStatusResolver.Live> live = statusResolver.resolve(all);
        Map<UUID, String> names = new java.util.HashMap<>();
        profiles.findAll().forEach(p -> names.put(p.getId(), p.getName()));
        return all.stream().map(m -> MachineView.of(m, live.get(m.getId()), packViews(m), names.get(m.getPriceProfileId()))).toList();
    }

    @Transactional(readOnly = true)
    public MachineView view(UUID id) {
        Machine m = get(id);
        String profileName = m.getPriceProfileId() == null ? null
                : profiles.findById(m.getPriceProfileId()).map(PriceProfile::getName).orElse(null);
        return MachineView.of(m, statusResolver.resolve(m), packViews(m), profileName);
    }

    // ---- Danh mục máy ----

    @Transactional
    public MachineView create(CreateMachine body) {
        if (machines.existsByCode(body.code())) throw new BusinessException(ErrorCode.CONFLICT, "Số hiệu máy đã tồn tại");
        Machine m = new Machine();
        m.setCode(body.code());
        m.setMachineType(body.type());
        m.setCapacityKg(body.capacityKg());
        m.setCycleMinutes(body.cycleMinutes());
        m.setAutoDosing(body.autoDosing());
        m.setDisplayOrder(body.displayOrder());
        machines.saveAndFlush(m);
        audit.user("MACHINE_CREATED", "machine", m.getId().toString(), "Thêm máy " + m.getCode(), null,
                Map.of("code", m.getCode(), "type", m.getMachineType(), "capacityKg", m.getCapacityKg()));
        return view(m.getId());
    }

    @Transactional
    public MachineView update(UUID id, UpdateMachine body) {
        Machine m = get(id);
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        if (body.capacityKg() != null && body.capacityKg().compareTo(m.getCapacityKg()) != 0) {
            before.put("capacityKg", m.getCapacityKg());
            after.put("capacityKg", body.capacityKg());
            m.setCapacityKg(body.capacityKg());
        }
        if (body.cycleMinutes() != null && body.cycleMinutes() != m.getCycleMinutes()) {
            before.put("cycleMinutes", m.getCycleMinutes());
            after.put("cycleMinutes", body.cycleMinutes());
            m.setCycleMinutes(body.cycleMinutes());
            detachProfile(m, before, after);
        }
        if (body.autoDosing() != null) m.setAutoDosing(body.autoDosing());
        if (body.displayOrder() != null) m.setDisplayOrder(body.displayOrder());
        audit.user("MACHINE_UPDATED", "machine", id.toString(), Reasons.require(body.reason()), before, after);
        return view(id);
    }

    /** Đổi giá (§2: thao tác nhạy cảm). Đơn đã đặt giữ giá cũ (order_legs.price_amount). */
    @Transactional
    public MachineView setPrice(UUID id, Long price, String reason) {
        Machine m = get(id);
        Map<String, Object> before = mapOf("priceVnd", m.getPriceVnd());
        Map<String, Object> after = mapOf("priceVnd", price);
        m.setPriceVnd(price);
        if (!java.util.Objects.equals(before.get("priceVnd"), price)) detachProfile(m, before, after);
        audit.user("MACHINE_PRICE_CHANGED", "machine", id.toString(), Reasons.require(reason), before, after);
        return view(id);
    }

    /** Đặt giá/số phút riêng cho một máy đang gắn profile: máy tách khỏi profile, sửa profile sau này không đổi máy này. */
    private static void detachProfile(Machine m, Map<String, Object> before, Map<String, Object> after) {
        if (m.getPriceProfileId() == null) return;
        before.put("priceProfileId", m.getPriceProfileId());
        after.put("priceProfileId", null);
        m.setPriceProfileId(null);
    }

    /**
     * Nhân viên chuyển máy sang Lỗi / Bảo trì / Ngừng dùng, hoặc trả về dùng được.
     * Các đơn đã gán cho máy được xử lý ngay theo §12 (module đặt lượt nghe {@link MachineAvailabilityChanged}).
     */
    @Transactional
    public MachineView setStatus(UUID id, Machine.AdminStatus status, String rawReason) {
        String reason = Reasons.require(rawReason);
        Machine m = machines.lock(id).orElseThrow(() -> BusinessException.notFound("máy"));
        Machine.AdminStatus before = m.getAdminStatus();
        if (before == status) return view(id);
        m.setAdminStatus(status);
        m.setStatusReason(status == Machine.AdminStatus.ACTIVE ? null : reason);
        m.setStatusChangedAt(clock.instant());
        // Lỗi / bảo trì / ngừng dùng: muốn cho thuê lại phải self-test đạt rồi đưa vào hoạt động (mục Lắp đặt).
        if (status != Machine.AdminStatus.ACTIVE) m.requireRetest(clock.instant());
        m.setStatusChangedBy(Actor.userId());
        audit.user("MACHINE_STATUS_CHANGED", "machine", id.toString(), reason, Map.of("status", before),
                Map.of("status", status));
        events.publishEvent(new MachineAvailabilityChanged(id, status == Machine.AdminStatus.ACTIVE, reason));
        return view(id);
    }

    // ---- Gói sấy thêm ----

    @Transactional
    public PackView addPack(UUID machineId, MachineDtos.PackBody body) {
        Machine m = get(machineId);
        if (m.getMachineType() != Machine.Type.DRYER) {
            throw new BusinessException(ErrorCode.MACHINE_TYPE_MISMATCH, "Chỉ máy sấy mới có gói sấy thêm");
        }
        DryerTimePack p = new DryerTimePack();
        p.setMachineId(machineId);
        p.setMinutes(body.minutes());
        p.setPriceVnd(body.priceVnd());
        p.setActive(body.active());
        packs.saveAndFlush(p);
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>(Map.of("minutes", body.minutes(), "priceVnd", body.priceVnd()));
        detachProfile(m, before, after);
        audit.user("DRYER_PACK_ADDED", "machine", machineId.toString(), Reasons.require(body.reason()), before, after);
        return PackView.of(p);
    }

    @Transactional
    public PackView updatePack(UUID machineId, UUID packId, MachineDtos.PackBody body) {
        DryerTimePack p = packs.findById(packId).filter(x -> x.getMachineId().equals(machineId))
                .orElseThrow(() -> BusinessException.notFound("gói sấy"));
        Map<String, Object> before = new LinkedHashMap<>(Map.of("minutes", p.getMinutes(), "priceVnd", p.getPriceVnd(), "active", p.isActive()));
        Map<String, Object> after = new LinkedHashMap<>(Map.of("minutes", body.minutes(), "priceVnd", body.priceVnd(), "active", body.active()));
        p.setMinutes(body.minutes());
        p.setPriceVnd(body.priceVnd());
        p.setActive(body.active());
        detachProfile(get(machineId), before, after);
        audit.user("DRYER_PACK_CHANGED", "machine", machineId.toString(), Reasons.require(body.reason()), before, after);
        return PackView.of(p);
    }

    // ---- Lịch bảo trì (§6.3) ----

    public List<MaintenanceView> maintenance(UUID machineId) {
        return reservations.upcomingMaintenance(machineId, clock.instant()).stream().map(MaintenanceView::of).toList();
    }

    /** Trùng lượt đã đặt thì từ chối và liệt kê đơn bị trùng; chủ cửa hàng chuyển/hủy đơn trước (§16). */
    @Transactional
    public MaintenanceView addMaintenance(UUID machineId, Instant startsAt, Instant endsAt, String rawReason) {
        String reason = Reasons.require(rawReason);
        get(machineId);
        if (!endsAt.isAfter(startsAt)) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Giờ kết thúc phải sau giờ bắt đầu");
        List<String> clashing = jdbc.queryForList("""
                select distinct coalesce(o.code, 'BẢO TRÌ') from machine_reservations r
                left join order_legs l on l.reservation_id = r.id left join orders o on o.id = l.order_id
                where r.machine_id = ? and r.active and r.starts_at < ? and r.ends_at > ?
                """, String.class, machineId, Timestamp.from(endsAt), Timestamp.from(startsAt));
        if (!clashing.isEmpty()) {
            throw new BusinessException(ErrorCode.SCHEDULE_CONFLICT,
                    "Khoảng bảo trì trùng " + clashing.size() + " lượt đã có; chuyển hoặc hủy các đơn đó trước",
                    Map.of("orders", clashing));
        }
        MachineReservation r = new MachineReservation();
        r.setMachineId(machineId);
        r.setKind(MachineReservation.Kind.MAINTENANCE);
        r.setStartsAt(startsAt);
        r.setEndsAt(endsAt);
        r.setNote(reason);
        r.setCreatedBy(Actor.userId());
        reservations.saveAndFlush(r);
        audit.user("MACHINE_MAINTENANCE_SCHEDULED", "machine", machineId.toString(), reason, null,
                Map.of("startsAt", startsAt.toString(), "endsAt", endsAt.toString()));
        return MaintenanceView.of(r);
    }

    @Transactional
    public void cancelMaintenance(UUID machineId, UUID reservationId, String reason) {
        MachineReservation r = reservations.lock(reservationId)
                .filter(x -> x.getMachineId().equals(machineId) && x.getKind() == MachineReservation.Kind.MAINTENANCE)
                .orElseThrow(() -> BusinessException.notFound("lịch bảo trì"));
        r.setActive(false);
        audit.user("MACHINE_MAINTENANCE_CANCELLED", "machine", machineId.toString(), Reasons.require(reason),
                Map.of("startsAt", r.getStartsAt().toString(), "endsAt", r.getEndsAt().toString()), null);
    }

    private List<PackView> packViews(Machine m) {
        if (m.getMachineType() != Machine.Type.DRYER) return List.of();
        return packs.findByMachineIdOrderByDisplayOrderAscMinutesAsc(m.getId()).stream().map(PackView::of).toList();
    }

    private static Map<String, Object> mapOf(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }
}
