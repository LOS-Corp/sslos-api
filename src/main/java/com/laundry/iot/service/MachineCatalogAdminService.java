package com.laundry.iot.service;

import com.laundry.iot.dto.ProvisioningDtos.ApproveDeviceRequest;
import com.laundry.iot.dto.ProvisioningDtos.DeviceAsset;
import com.laundry.iot.dto.MachineCatalogDtos.BrandRequest;
import com.laundry.iot.dto.MachineCatalogDtos.BrandView;
import com.laundry.iot.dto.MachineCatalogDtos.ModelRequest;
import com.laundry.iot.dto.MachineCatalogDtos.ModelView;
import com.laundry.iot.entity.MachineBrand;
import com.laundry.iot.entity.MachineModel;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.iot.repository.MachineBrandRepository;
import com.laundry.iot.repository.MachineModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD of the machine brand/model catalog (V63). Names are unique (a brand in the store, a model within its brand,
 * ignoring case). A brand with models, or a model used by a device, cannot be deleted.
 */
@Service
@RequiredArgsConstructor
public class MachineCatalogAdminService {

    private final MachineBrandRepository brands;
    private final MachineModelRepository models;
    private final IotDeviceRepository devices;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public List<BrandView> list() {
        Map<UUID, Long> usage = new HashMap<>();
        jdbc.query("SELECT model_id, count(*) FROM iot_devices WHERE model_id IS NOT NULL GROUP BY model_id",
                rs -> { usage.put(rs.getObject(1, UUID.class), rs.getLong(2)); });
        Map<UUID, List<ModelView>> byBrand = models.findAllByOrderByNameAsc().stream()
                .map(m -> view(m, usage.getOrDefault(m.getId(), 0L)))
                .collect(Collectors.groupingBy(ModelView::brandId));
        return brands.findAllByOrderByNameAsc().stream()
                .map(b -> new BrandView(b.getId(), b.getName(), byBrand.getOrDefault(b.getId(), List.of())))
                .toList();
    }

    // ── Brands ─────────────────────────────────────────────────────────────

    @Transactional
    public BrandView createBrand(BrandRequest request) {
        String name = request.name().trim();
        if (brands.existsByNameIgnoreCase(name)) throw conflict("Hãng " + name + " đã có trong danh mục");
        MachineBrand brand = brands.save(MachineBrand.builder().name(name).build());
        return new BrandView(brand.getId(), brand.getName(), List.of());
    }

    @Transactional
    public BrandView renameBrand(UUID id, BrandRequest request) {
        MachineBrand brand = brand(id);
        String name = request.name().trim();
        if (brands.existsByNameIgnoreCaseAndIdNot(name, id)) throw conflict("Hãng " + name + " đã có trong danh mục");
        brand.setName(name);
        brands.save(brand);
        // Devices show the catalog names: keep their copies in step.
        jdbc.update("""
                UPDATE iot_devices SET manufacturer = ?
                WHERE model_id IN (SELECT id FROM machine_models WHERE brand_id = ?)""", name, id);
        return list().stream().filter(b -> b.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteBrand(UUID id) {
        MachineBrand brand = brand(id);
        if (models.existsByBrandId(id)) {
            throw conflict("Hãng " + brand.getName() + " còn model. Xóa các model của hãng trước.");
        }
        brands.delete(brand);
    }

    // ── Models ─────────────────────────────────────────────────────────────

    @Transactional
    public ModelView createModel(UUID brandId, ModelRequest request) {
        MachineBrand brand = brand(brandId);
        String name = request.name().trim();
        if (models.existsByBrandIdAndNameIgnoreCase(brandId, name)) {
            throw conflict("Model " + name + " của " + brand.getName() + " đã có trong danh mục");
        }
        MachineModel model = models.save(MachineModel.builder()
                .brandId(brandId).name(name).machineType(request.machineType()).build());
        return view(model, 0);
    }

    @Transactional
    public ModelView updateModel(UUID id, ModelRequest request) {
        MachineModel model = model(id);
        String name = request.name().trim();
        if (models.existsByBrandIdAndNameIgnoreCaseAndIdNot(model.getBrandId(), name, id)) {
            throw conflict("Model " + name + " đã có trong hãng này");
        }
        model.setName(name);
        model.setMachineType(request.machineType());
        models.save(model);
        // Devices show the catalog names: keep their copies in step.
        jdbc.update("UPDATE iot_devices SET model = ? WHERE model_id = ?", name, id);
        return view(model, usage(id));
    }

    @Transactional
    public void deleteModel(UUID id) {
        MachineModel model = model(id);
        if (devices.existsByModelId(id)) {
            throw conflict("Model " + model.getName() + " đang được thiết bị sử dụng, không xóa được.");
        }
        models.delete(model);
    }

    /** Device approval: the request with its catalog model resolved to brand and model names. */
    @Transactional(readOnly = true)
    public DeviceAsset asset(ApproveDeviceRequest request) {
        MachineModel model = model(request.modelId());
        String specs = request.technicalSpecs() == null || request.technicalSpecs().isBlank()
                ? null : request.technicalSpecs().trim();
        return new DeviceAsset(model.getId(), brand(model.getBrandId()).getName(), model.getName(),
                model.getMachineType(), request.purchaseCost(), request.warrantyUntil(), specs);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private MachineBrand brand(UUID id) {
        return brands.findById(id).orElseThrow(() ->
                new ProvisioningRejectedException(HttpStatus.NOT_FOUND, "Không tìm thấy hãng máy"));
    }

    private MachineModel model(UUID id) {
        return models.findById(id).orElseThrow(() ->
                new ProvisioningRejectedException(HttpStatus.NOT_FOUND, "Không tìm thấy model máy"));
    }

    private long usage(UUID modelId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM iot_devices WHERE model_id = ?", Long.class, modelId);
        return count == null ? 0 : count;
    }

    private static ModelView view(MachineModel m, long deviceCount) {
        return new ModelView(m.getId(), m.getBrandId(), m.getName(), m.getMachineType(), deviceCount);
    }

    private static ProvisioningRejectedException conflict(String message) {
        return new ProvisioningRejectedException(HttpStatus.CONFLICT, message);
    }
}
