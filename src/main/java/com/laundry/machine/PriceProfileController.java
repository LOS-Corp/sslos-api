package com.laundry.machine;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Profile giá thuê: Quản trị viên và Chủ cửa hàng xem; chỉ Chủ cửa hàng thêm, sửa, xóa và gán cho máy. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Profile giá thuê")
public class PriceProfileController {

    private final PriceProfileService service;
    private final MachineService machines;

    /** Gói bán thêm thời gian (chỉ profile máy sấy). active null = đang bán. */
    public record PackBody(@Min(1) @Max(120) int minutes, @Min(1000) long priceVnd, Boolean active) {
        PriceProfileService.PackView toView() {
            return new PriceProfileService.PackView(minutes, priceVnd, active == null || active);
        }
    }

    /** capacityKg: giá thuê theo công suất, profile chỉ gắn cho máy đúng số kg này; null = mọi công suất. */
    public record CreateBody(@NotBlank @Size(max = 80) String name, @Size(max = 300) String description,
                             @NotNull Machine.Type machineType, @Min(1000) long priceVnd, @Min(1) @Max(240) int cycleMinutes,
                             @Valid List<PackBody> packs,
                             @DecimalMin("1.0") @DecimalMax("99.9") java.math.BigDecimal capacityKg) {
    }

    public record UpdateBody(@NotBlank @Size(max = 80) String name, @Size(max = 300) String description,
                             Machine.Type machineType, @Min(1000) long priceVnd, @Min(1) @Max(240) int cycleMinutes,
                             @Valid List<PackBody> packs,
                             @DecimalMin("1.0") @DecimalMax("99.9") java.math.BigDecimal capacityKg,
                             @NotBlank String reason) {
    }

    public record ReasonBody(@NotBlank String reason) {
    }

    private static List<PriceProfileService.PackView> packs(List<PackBody> packs) {
        return packs == null ? List.of() : packs.stream().map(PackBody::toView).toList();
    }

    /** profileId null = gỡ khỏi profile, máy giữ giá hiện tại làm giá riêng. */
    public record AssignBody(UUID profileId, @NotBlank String reason) {
    }

    @GetMapping({"/api/admin/price-profiles", "/api/owner/price-profiles"})
    public List<PriceProfileService.ProfileView> list() {
        return service.list();
    }

    @PostMapping("/api/owner/price-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    public PriceProfileService.ProfileView create(@Valid @RequestBody CreateBody body) {
        return service.create(new PriceProfileService.ProfileInput(body.name(), body.description(), body.machineType(),
                body.priceVnd(), body.cycleMinutes(), packs(body.packs()), body.capacityKg()));
    }

    @PostMapping("/api/owner/price-profiles/{id}/apply-by-capacity")
    @Operation(summary = "Gắn profile cho mọi máy cùng loại, cùng công suất (profile phải đặt công suất)")
    public PriceProfileService.ApplyResult applyByCapacity(@PathVariable UUID id, @Valid @RequestBody ReasonBody body) {
        return service.applyByCapacity(id, body.reason());
    }

    @PutMapping("/api/owner/price-profiles/{id}")
    @Operation(summary = "Sửa profile: mọi máy gắn profile đổi giá, số phút, gói sấy thêm theo ngay; lượt đã đặt giữ giá cũ")
    public PriceProfileService.ProfileView update(@PathVariable UUID id, @Valid @RequestBody UpdateBody body) {
        return service.update(id, new PriceProfileService.ProfileInput(body.name(), body.description(), body.machineType(),
                body.priceVnd(), body.cycleMinutes(), packs(body.packs()), body.capacityKg()), body.reason());
    }

    @DeleteMapping("/api/owner/price-profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa profile không còn máy nào dùng")
    public void delete(@PathVariable UUID id, @RequestParam String reason) {
        service.delete(id, reason);
    }

    @PutMapping("/api/owner/machines/{id}/price-profile")
    @Operation(summary = "Gắn máy vào profile (giá và số phút theo profile) hoặc gỡ ra (profileId null)")
    public MachineDtos.MachineView assign(@PathVariable UUID id, @Valid @RequestBody AssignBody body) {
        service.assign(id, body.profileId(), body.reason());
        return machines.view(id);
    }
}
