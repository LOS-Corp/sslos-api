package com.laundry.locker;

import com.laundry.audit.AuditService;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

@RestController
@RequiredArgsConstructor
@Tag(name = "Quản lý tủ locker")
public class LockerAdminController {

    private final LockerRepository lockers;
    private final LockerCompartmentRepository compartments;
    private final AuditService audit;

    public record CreateLocker(@NotBlank @Pattern(regexp = "^[A-Z0-9-]{1,10}$") String code,
                               @Size(max = 120) String name,
                               @Min(1) @Max(24) int compartments) {
    }

    public record CompartmentView(UUID id, int no, String adminStatus, Boolean doorClosed, Boolean unlocked,
                                  Instant stateUpdatedAt) {
    }

    public record LockerView(UUID id, String code, String name, String adminStatus, List<CompartmentView> compartments) {
    }

    @GetMapping({"/api/admin/lockers", "/api/owner/lockers", "/api/staff/lockers"})
    @Transactional(readOnly = true)
    public List<LockerView> list() {
        return lockers.findAll().stream().map(this::view).toList();
    }

    @PostMapping({"/api/admin/lockers", "/api/owner/lockers"})
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public LockerView create(@Valid @RequestBody CreateLocker body) {
        if (lockers.existsByCode(body.code())) throw new BusinessException(ErrorCode.CONFLICT, "Mã tủ đã tồn tại");
        Locker l = new Locker();
        l.setCode(body.code());
        l.setName(body.name());
        lockers.saveAndFlush(l);
        compartments.saveAll(IntStream.rangeClosed(1, body.compartments()).mapToObj(no -> {
            LockerCompartment c = new LockerCompartment();
            c.setLockerId(l.getId());
            c.setCompartmentNo(no);
            return c;
        }).toList());
        audit.user("LOCKER_CREATED", "locker", l.getId().toString(), "Thêm tủ " + l.getCode(), null,
                Map.of("compartments", body.compartments()));
        return view(l);
    }

    private LockerView view(Locker l) {
        return new LockerView(l.getId(), l.getCode(), l.getName(), l.getAdminStatus(),
                compartments.findByLockerIdOrderByCompartmentNo(l.getId()).stream()
                        .map(c -> new CompartmentView(c.getId(), c.getCompartmentNo(), c.getAdminStatus(),
                                c.getDoorClosed(), c.getUnlocked(), c.getStateUpdatedAt())).toList());
    }
}
