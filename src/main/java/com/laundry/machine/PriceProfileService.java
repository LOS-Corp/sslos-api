package com.laundry.machine;

import com.laundry.audit.AuditService;
import com.laundry.common.Reasons;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Profile giá thuê (V3). Máy gắn profile thì giá một lượt, số phút một lượt và (máy sấy) các gói bán thêm thời gian của
 * máy luôn bằng profile: tạo/gắn/sửa
 * profile đều ghi thẳng vào machines.price_vnd / cycle_minutes trong cùng giao dịch, nên luồng đặt lượt không đổi và lượt
 * đã đặt giữ giá cũ (order_legs chụp giá lúc đặt). Chỉ Chủ cửa hàng sửa; Quản trị viên chỉ xem.
 */
@Service
@RequiredArgsConstructor
public class PriceProfileService {

    private final PriceProfileRepository profiles;
    private final PriceProfilePackRepository profilePacks;
    private final DryerTimePackRepository machinePacks;
    private final MachineRepository machines;
    private final AuditService audit;

    /** Giới hạn số gói bán thêm của một profile cho trang mua thêm gọn. */
    private static final int MAX_PACKS = 6;

    /** Gói bán thêm thời gian (chỉ profile máy sấy): khách đang sấy mua thêm `minutes` phút với giá `priceVnd`. */
    public record PackView(int minutes, long priceVnd, boolean active) {
    }

    public record ProfileView(UUID id, String name, String description, Machine.Type machineType, long priceVnd,
                              int cycleMinutes, List<PackView> packs, int machineCount, List<String> machineCodes,
                              Instant updatedAt) {
    }

    public record ProfileInput(String name, String description, Machine.Type machineType, long priceVnd, int cycleMinutes,
                               List<PackView> packs) {
    }

    @Transactional(readOnly = true)
    public List<ProfileView> list() {
        Map<UUID, List<String>> codes = new HashMap<>();
        for (Machine m : machines.listAll()) {
            if (m.getPriceProfileId() != null) codes.computeIfAbsent(m.getPriceProfileId(), k -> new ArrayList<>()).add(m.getCode());
        }
        Map<UUID, List<PriceProfilePack>> packs = profilePacks.listAll().stream()
                .collect(Collectors.groupingBy(PriceProfilePack::getProfileId));
        return profiles.listAll().stream()
                .map(p -> view(p, packs.getOrDefault(p.getId(), List.of()), codes.getOrDefault(p.getId(), List.of()))).toList();
    }

    @Transactional
    public ProfileView create(ProfileInput in) {
        requireUniqueName(in.name(), null);
        PriceProfile p = new PriceProfile();
        apply(p, in);
        p.setMachineType(in.machineType());
        List<PackView> packs = validPacks(p.getMachineType(), in.packs());
        profiles.saveAndFlush(p);
        List<PriceProfilePack> saved = replacePacks(p.getId(), packs);
        audit.user("PRICE_PROFILE_CREATED", "price_profile", p.getId().toString(), "Thêm profile " + p.getName(), null,
                snapshot(p, saved));
        return view(p, saved, List.of());
    }

    /** Sửa profile: mọi máy gắn profile đổi giá/số phút theo ngay. Lượt đã đặt giữ giá cũ. */
    @Transactional
    public ProfileView update(UUID id, ProfileInput in, String rawReason) {
        String reason = Reasons.require(rawReason);
        PriceProfile p = profiles.lock(id).orElseThrow(() -> BusinessException.notFound("profile giá"));
        if (in.machineType() != null && in.machineType() != p.getMachineType()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Không đổi được loại máy của profile; tạo profile mới");
        }
        requireUniqueName(in.name(), id);
        List<PackView> packs = validPacks(p.getMachineType(), in.packs());
        Map<String, Object> before = snapshot(p, profilePacks.findByProfileIdOrderByDisplayOrderAscMinutesAsc(id));
        apply(p, in);
        List<PriceProfilePack> saved = replacePacks(id, packs);
        List<Machine> attached = machines.findByPriceProfileIdOrderByCode(id);
        for (Machine m : attached) syncFrom(m, p, saved);
        Map<String, Object> after = snapshot(p, saved);
        after.put("machines", attached.stream().map(Machine::getCode).toList());
        audit.user("PRICE_PROFILE_CHANGED", "price_profile", id.toString(), reason, before, after);
        return view(p, saved, attached.stream().map(Machine::getCode).toList());
    }

    @Transactional
    public void delete(UUID id, String rawReason) {
        String reason = Reasons.require(rawReason);
        PriceProfile p = profiles.lock(id).orElseThrow(() -> BusinessException.notFound("profile giá"));
        List<Machine> attached = machines.findByPriceProfileIdOrderByCode(id);
        if (!attached.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Profile đang có " + attached.size() + " máy dùng ("
                    + String.join(", ", attached.stream().map(Machine::getCode).toList()) + "); gán các máy sang profile khác trước");
        }
        audit.user("PRICE_PROFILE_DELETED", "price_profile", id.toString(), reason,
                snapshot(p, profilePacks.findByProfileIdOrderByDisplayOrderAscMinutesAsc(id)), null);
        profiles.delete(p);
    }

    /**
     * Gắn máy vào profile (giá và số phút của máy lấy theo profile), hoặc gỡ ra (profileId = null: máy giữ giá hiện tại
     * làm giá riêng). Profile phải cùng loại máy.
     */
    @Transactional
    public void assign(UUID machineId, UUID profileId, String rawReason) {
        String reason = Reasons.require(rawReason);
        Machine m = machines.lock(machineId).orElseThrow(() -> BusinessException.notFound("máy"));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("priceProfileId", m.getPriceProfileId());
        before.put("priceVnd", m.getPriceVnd());
        before.put("cycleMinutes", m.getCycleMinutes());
        if (profileId == null) {
            m.setPriceProfileId(null);
        } else {
            PriceProfile p = profiles.findById(profileId).orElseThrow(() -> BusinessException.notFound("profile giá"));
            if (p.getMachineType() != m.getMachineType()) {
                throw new BusinessException(ErrorCode.MACHINE_TYPE_MISMATCH, "Profile " + p.getName() + " dành cho "
                        + (p.getMachineType() == Machine.Type.DRYER ? "máy sấy" : "máy giặt"));
            }
            m.setPriceProfileId(p.getId());
            syncFrom(m, p, profilePacks.findByProfileIdOrderByDisplayOrderAscMinutesAsc(p.getId()));
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("priceProfileId", m.getPriceProfileId());
        after.put("priceVnd", m.getPriceVnd());
        after.put("cycleMinutes", m.getCycleMinutes());
        audit.user("MACHINE_PRICE_PROFILE_SET", "machine", machineId.toString(), reason, before, after);
    }

    /**
     * Máy theo profile: giá, số phút và (máy sấy) gói bán thêm. Gói của máy khớp theo số phút: trùng thì cập nhật giá và
     * bật/tắt, thiếu thì thêm, gói máy không còn trong profile thì tắt (không xóa: dryer_extensions đã bán còn trỏ tới).
     */
    private void syncFrom(Machine m, PriceProfile p, List<PriceProfilePack> packs) {
        m.setPriceVnd(p.getPriceVnd());
        m.setCycleMinutes(p.getCycleMinutes());
        if (m.getMachineType() != Machine.Type.DRYER) return;
        Map<Integer, DryerTimePack> current = machinePacks.findByMachineIdOrderByDisplayOrderAscMinutesAsc(m.getId()).stream()
                .collect(Collectors.toMap(DryerTimePack::getMinutes, Function.identity()));
        Set<Integer> wanted = new HashSet<>();
        for (PriceProfilePack pp : packs) {
            wanted.add(pp.getMinutes());
            DryerTimePack mp = current.get(pp.getMinutes());
            if (mp == null) {
                mp = new DryerTimePack();
                mp.setMachineId(m.getId());
                mp.setMinutes(pp.getMinutes());
            }
            mp.setPriceVnd(pp.getPriceVnd());
            mp.setActive(pp.isActive());
            mp.setDisplayOrder(pp.getDisplayOrder());
            machinePacks.save(mp);
        }
        current.values().stream().filter(mp -> !wanted.contains(mp.getMinutes())).forEach(mp -> mp.setActive(false));
    }

    /** Gói của profile khớp theo số phút: cập nhật gói trùng, thêm gói mới, xóa gói không còn. */
    private List<PriceProfilePack> replacePacks(UUID profileId, List<PackView> packs) {
        Map<Integer, PriceProfilePack> current = profilePacks.findByProfileIdOrderByDisplayOrderAscMinutesAsc(profileId).stream()
                .collect(Collectors.toMap(PriceProfilePack::getMinutes, Function.identity()));
        List<PriceProfilePack> out = new ArrayList<>();
        int order = 0;
        for (PackView in : packs) {
            PriceProfilePack pp = current.remove(in.minutes());
            if (pp == null) {
                pp = new PriceProfilePack();
                pp.setProfileId(profileId);
                pp.setMinutes(in.minutes());
            }
            pp.setPriceVnd(in.priceVnd());
            pp.setActive(in.active());
            pp.setDisplayOrder(order++);
            out.add(profilePacks.save(pp));
        }
        profilePacks.deleteAll(current.values());
        profilePacks.flush();
        return out;
    }

    private static List<PackView> validPacks(Machine.Type type, List<PackView> packs) {
        List<PackView> list = packs == null ? List.of() : packs;
        if (list.isEmpty()) return list;
        if (type != Machine.Type.DRYER) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Chỉ profile máy sấy mới bán thêm thời gian");
        }
        if (list.size() > MAX_PACKS) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Tối đa " + MAX_PACKS + " gói bán thêm");
        Set<Integer> seen = new HashSet<>();
        for (PackView p : list) {
            if (p.minutes() < 1 || p.minutes() > 120) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Gói thêm từ 1 đến 120 phút");
            if (p.priceVnd() < 1000) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Giá gói thêm tối thiểu 1.000đ");
            if (!seen.add(p.minutes())) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Hai gói thêm trùng " + p.minutes() + " phút");
        }
        return list.stream().sorted(Comparator.comparingInt(PackView::minutes)).toList();
    }

    private static void apply(PriceProfile p, ProfileInput in) {
        p.setName(in.name().trim());
        p.setDescription(in.description() == null || in.description().isBlank() ? null : in.description().trim());
        p.setPriceVnd(in.priceVnd());
        p.setCycleMinutes(in.cycleMinutes());
    }

    private void requireUniqueName(String name, UUID except) {
        String key = name.trim().toLowerCase();
        boolean taken = profiles.findAll().stream()
                .anyMatch(p -> p.getName().trim().toLowerCase().equals(key) && !p.getId().equals(except));
        if (taken) throw new BusinessException(ErrorCode.CONFLICT, "Đã có profile tên " + name.trim());
    }

    private static Map<String, Object> snapshot(PriceProfile p, List<PriceProfilePack> packs) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("machineType", p.getMachineType());
        m.put("priceVnd", p.getPriceVnd());
        m.put("cycleMinutes", p.getCycleMinutes());
        if (p.getMachineType() == Machine.Type.DRYER) {
            m.put("packs", packs.stream().map(x -> x.getMinutes() + " phút " + x.getPriceVnd() + (x.isActive() ? "" : " (tắt)")).toList());
        }
        return m;
    }

    private static ProfileView view(PriceProfile p, List<PriceProfilePack> packs, List<String> codes) {
        return new ProfileView(p.getId(), p.getName(), p.getDescription(), p.getMachineType(), p.getPriceVnd(),
                p.getCycleMinutes(), packs.stream().map(x -> new PackView(x.getMinutes(), x.getPriceVnd(), x.isActive())).toList(),
                codes.size(), codes, p.getUpdatedAt());
    }
}
