package com.laundry.device.asset;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

public final class DeviceAssetControllers {

    private DeviceAssetControllers() {
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Admin · Danh mục hãng/model máy")
    public static class MachineCatalogController {
        private final MachineCatalogService catalog;

        public record NameBody(@NotBlank @Size(max = 80) String name) {
        }

        /** machineType: WASHER / DRYER, null = dùng cho cả hai. */
        public record ModelBody(@NotBlank @Size(max = 80) String name, String machineType) {
        }

        @GetMapping("/api/admin/machine-brands")
        @Operation(summary = "Mọi hãng kèm model, xếp theo tên; deviceCount = số bo đang dùng model")
        public List<MachineCatalogService.BrandView> list() {
            return catalog.list();
        }

        @PostMapping("/api/admin/machine-brands")
        @ResponseStatus(HttpStatus.CREATED)
        public MachineCatalogService.BrandView createBrand(@Valid @RequestBody NameBody body) {
            return catalog.createBrand(body.name());
        }

        @PutMapping("/api/admin/machine-brands/{id}")
        public MachineCatalogService.BrandView renameBrand(@PathVariable UUID id, @Valid @RequestBody NameBody body) {
            return catalog.renameBrand(id, body.name());
        }

        @DeleteMapping("/api/admin/machine-brands/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @Operation(summary = "Chỉ xóa được hãng không còn model")
        public void deleteBrand(@PathVariable UUID id) {
            catalog.deleteBrand(id);
        }

        @PostMapping("/api/admin/machine-brands/{id}/models")
        @ResponseStatus(HttpStatus.CREATED)
        public MachineCatalogService.ModelView createModel(@PathVariable UUID id, @Valid @RequestBody ModelBody body) {
            return catalog.createModel(id, body.name(), body.machineType());
        }

        @PutMapping("/api/admin/machine-brands/models/{id}")
        public MachineCatalogService.ModelView updateModel(@PathVariable UUID id, @Valid @RequestBody ModelBody body) {
            return catalog.updateModel(id, body.name(), body.machineType());
        }

        @DeleteMapping("/api/admin/machine-brands/models/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @Operation(summary = "Chỉ xóa được model không có bo nào dùng")
        public void deleteModel(@PathVariable UUID id) {
            catalog.deleteModel(id);
        }
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Admin · Tài sản thiết bị")
    public static class DeviceAssetController {
        private final DeviceAssetService assets;

        @GetMapping("/api/admin/devices/assets")
        @Operation(summary = "Tài sản của mọi bo (ghép với GET /api/admin/devices theo deviceId)")
        public List<DeviceAssetService.AssetView> list() {
            return assets.list();
        }

        @GetMapping("/api/admin/devices/{id}/asset")
        public DeviceAssetService.AssetView get(@PathVariable String id) {
            return assets.get(id);
        }

        @PutMapping("/api/admin/devices/{id}/asset")
        @Operation(summary = "Sửa model, giá mua, hạn bảo hành, thông số (ghi nhật ký trước/sau)")
        public DeviceAssetService.AssetView update(@PathVariable String id, @Valid @RequestBody DeviceAssetService.AssetInput body) {
            return assets.update(id, body, "Sửa thông tin tài sản của bo");
        }

        @GetMapping("/api/admin/devices/{id}/stats")
        @Operation(summary = "Hoàn vốn: lượt chạy và doanh thu của máy đang gắn bo, tính từ lúc gắn, so với giá mua")
        public DeviceAssetService.StatsView stats(@PathVariable String id) {
            return assets.stats(id);
        }
    }
}
