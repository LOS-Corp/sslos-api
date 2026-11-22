package com.laundry.machine.service;

import com.laundry.machine.dto.PriceProfileDtos.PriceProfileRequest;
import com.laundry.machine.dto.PriceProfileDtos.PriceProfileView;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.entity.PriceProfile;
import com.laundry.machine.repository.PriceProfileRepository;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CRUD of price profiles (V64). Names are unique ignoring case; a profile charges a price per cycle above 0 (V65);
 * a profile used by a machine cannot be deleted, and its machine type cannot change.
 */
@Service
@RequiredArgsConstructor
public class PriceProfileService {

    /** Bounds of a cycle (V71): shorter is no real wash or dry and would let bookings overlap. */
    public static final int MIN_CYCLE_MINUTES = 10;
    public static final int MAX_CYCLE_MINUTES = 240;

    private final PriceProfileRepository profiles;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<PriceProfileView> list() {
        Map<UUID, Long> usage = new HashMap<>();
        jdbc.query("SELECT price_profile_id, count(*) FROM machines WHERE price_profile_id IS NOT NULL GROUP BY 1",
                rs -> { usage.put(rs.getObject(1, UUID.class), rs.getLong(2)); });
        return profiles.findAllByOrderByMachineTypeAscNameAsc().stream()
                .map(p -> view(p, usage.getOrDefault(p.getId(), 0L)))
                .toList();
    }

    @Transactional
    public PriceProfileView create(PriceProfileRequest request) {
        String name = request.name().trim();
        if (profiles.existsByNameIgnoreCase(name)) throw conflict("Profile " + name + " đã có");
        PriceProfile profile = PriceProfile.builder().build();
        apply(profile, request);
        return view(profiles.save(profile), 0);
    }

    @Transactional
    public PriceProfileView update(UUID id, PriceProfileRequest request) {
        PriceProfile profile = require(id);
        String name = request.name().trim();
        if (profiles.existsByNameIgnoreCaseAndIdNot(name, id)) throw conflict("Profile " + name + " đã có");
        long used = usage(id);
        if (used > 0 && request.machineType() != profile.getMachineType()) {
            throw conflict("Profile đang được " + used + " máy dùng, không đổi được loại máy");
        }
        apply(profile, request);
        return view(profiles.save(profile), used);
    }

    @Transactional
    public void delete(UUID id) {
        PriceProfile profile = require(id);
        long used = usage(id);
        if (used > 0) throw conflict("Profile " + profile.getName() + " đang được " + used + " máy dùng, không xóa được");
        profiles.delete(profile);
    }

    /** For assigning a profile to a machine: it must exist and fit the machine type. */
    @Transactional(readOnly = true)
    public PriceProfile requireFor(UUID id, MachineType machineType) {
        PriceProfile profile = require(id);
        if (profile.getMachineType() != machineType) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST,
                    "Profile " + profile.getName() + " dành cho " + profile.getMachineType() + ", máy là " + machineType);
        }
        return profile;
    }

    private static void apply(PriceProfile profile, PriceProfileRequest request) {
        if (request.basePrice().signum() <= 0) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Nhập giá mỗi lượt lớn hơn 0");
        }
        boolean extra = request.extraMinutes() != null || request.extraPrice() != null;
        if (extra && request.machineType() != MachineType.DRYER) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Chỉ máy sấy mới bán thêm thời gian");
        }
        if (extra && (request.extraMinutes() == null || request.extraPrice() == null)) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Nhập cả số phút và giá khi bán thêm thời gian sấy");
        }
        if (request.extraPrice() != null && request.extraPrice().signum() <= 0) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Nhập giá mua thêm lớn hơn 0");
        }
        profile.setName(request.name().trim());
        profile.setMachineType(request.machineType());
        profile.setBasePrice(request.basePrice());
        int cycle = request.cycleMinutes() != null ? request.cycleMinutes()
                : com.laundry.machine.dto.PriceProfileDtos.defaultCycleMinutes(request.machineType());
        // V71: the cycle holds the machine against the next booking, so it must be the machine's real run time.
        if (cycle < MIN_CYCLE_MINUTES || cycle > MAX_CYCLE_MINUTES) {
            throw BusinessException.of(ErrorCode.INVALID_REQUEST, "Thời gian một lượt phải từ " + MIN_CYCLE_MINUTES
                    + " đến " + MAX_CYCLE_MINUTES + " phút: đặt bằng thời gian chạy thật của máy");
        }
        profile.setCycleMinutes(cycle);
        profile.setDescription(request.description() == null || request.description().isBlank()
                ? null : request.description().trim());
        profile.setExtraMinutes(request.extraMinutes());
        profile.setExtraPrice(request.extraPrice());
    }

    private PriceProfile require(UUID id) {
        return profiles.findById(id).orElseThrow(() -> BusinessException.of(ErrorCode.NOT_FOUND, "Không tìm thấy profile giá"));
    }

    private long usage(UUID id) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM machines WHERE price_profile_id = ?", Long.class, id);
        return count == null ? 0 : count;
    }

    private static PriceProfileView view(PriceProfile p, long machineCount) {
        return new PriceProfileView(p.getId(), p.getName(), p.getMachineType(), p.getBasePrice(),
                p.getCycleMinutes(), p.getDescription(), p.getExtraMinutes(), p.getExtraPrice(), machineCount);
    }

    private static BusinessException conflict(String message) {
        return BusinessException.of(ErrorCode.CONFLICT, message);
    }
}
