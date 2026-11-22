package com.laundry.iot;

import com.laundry.iot.dto.MachineCatalogDtos.BrandRequest;
import com.laundry.iot.dto.MachineCatalogDtos.BrandView;
import com.laundry.iot.dto.MachineCatalogDtos.ModelRequest;
import com.laundry.iot.dto.MachineCatalogDtos.ModelView;
import com.laundry.iot.dto.ProvisioningDtos.ApproveDeviceRequest;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.service.DeviceRegistryService;
import com.laundry.iot.service.MachineCatalogAdminService;
import com.laundry.machine.entity.MachineType;
import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V63: brand/model catalog CRUD, and approving a device with a catalog model. */
@IntegrationTest
@DisplayName("Machine brand and model catalog")
class MachineCatalogIT {

    @Autowired private MachineCatalogAdminService catalog;
    @Autowired private DeviceRegistryService registry;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Seeds the common brands")
    void seeded() {
        assertThat(catalog.list()).extracting(BrandView::name).contains("Aimi", "LG", "Samsung");
    }

    @Test
    @DisplayName("Brands and models are unique ignoring case; in-use rows cannot be deleted")
    void crud() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        BrandView brand = catalog.createBrand(new BrandRequest("Hãng " + suffix));
        assertThatThrownBy(() -> catalog.createBrand(new BrandRequest("  hãng " + suffix.toUpperCase() + " ")))
                .isInstanceOf(ProvisioningRejectedException.class);

        ModelView model = catalog.createModel(brand.id(), new ModelRequest("M-" + suffix, MachineType.WASHER));
        assertThatThrownBy(() -> catalog.createModel(brand.id(), new ModelRequest("m-" + suffix, null)))
                .isInstanceOf(ProvisioningRejectedException.class);
        assertThatThrownBy(() -> catalog.deleteBrand(brand.id()))      // still has a model
                .isInstanceOf(ProvisioningRejectedException.class);

        // A device approved with the model: the model cannot be deleted, renames reach the device.
        String deviceId = "ESP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbc.update("INSERT INTO iot_devices (id, device_id, status, device_type, enrolled_at, updated_at) "
                + "VALUES (?, ?, 'UNCLAIMED', 'WASHER', NOW(), NOW())", UUID.randomUUID(), deviceId);
        var approved = registry.approve(deviceId, catalog.asset(new ApproveDeviceRequest(model.id(),
                new BigDecimal("9000000"), null, " ")), null);
        assertThat(approved.manufacturer()).isEqualTo("Hãng " + suffix);
        assertThat(approved.model()).isEqualTo("M-" + suffix);
        assertThat(approved.modelId()).isEqualTo(model.id());
        assertThat(approved.technicalSpecs()).isNull();
        assertThatThrownBy(() -> catalog.deleteModel(model.id())).isInstanceOf(ProvisioningRejectedException.class);
        assertThat(catalog.list()).filteredOn(b -> b.id().equals(brand.id())).singleElement()
                .satisfies(b -> assertThat(b.models()).singleElement()
                        .satisfies(m -> assertThat(m.deviceCount()).isEqualTo(1)));

        catalog.renameBrand(brand.id(), new BrandRequest("Hãng mới " + suffix));
        catalog.updateModel(model.id(), new ModelRequest("M2-" + suffix, MachineType.WASHER));
        assertThat(jdbc.queryForObject("SELECT manufacturer || '|' || model FROM iot_devices WHERE device_id = ?",
                String.class, deviceId)).isEqualTo("Hãng mới " + suffix + "|M2-" + suffix);

        // Free the model, then both can go.
        jdbc.update("UPDATE iot_devices SET model_id = NULL WHERE device_id = ?", deviceId);
        catalog.deleteModel(model.id());
        catalog.deleteBrand(brand.id());
        assertThat(catalog.list()).noneMatch(b -> b.id().equals(brand.id()));
    }

    @Test
    @DisplayName("A dryer model cannot be confirmed for a washer board")
    void typeMismatch() {
        BrandView brand = catalog.createBrand(new BrandRequest("Dry " + UUID.randomUUID().toString().substring(0, 6)));
        ModelView dryer = catalog.createModel(brand.id(), new ModelRequest("D1", MachineType.DRYER));
        String deviceId = "ESP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        jdbc.update("INSERT INTO iot_devices (id, device_id, status, device_type, enrolled_at, updated_at) "
                + "VALUES (?, ?, 'UNCLAIMED', 'WASHER', NOW(), NOW())", UUID.randomUUID(), deviceId);
        assertThatThrownBy(() -> registry.approve(deviceId, catalog.asset(new ApproveDeviceRequest(dryer.id(),
                BigDecimal.ONE, null, null)), null)).isInstanceOf(ProvisioningRejectedException.class);
    }
}
