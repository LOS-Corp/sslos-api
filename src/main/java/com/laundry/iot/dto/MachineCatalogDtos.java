package com.laundry.iot.dto;

import com.laundry.machine.entity.MachineType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Request/response bodies of the brand/model catalog (V63). */
public final class MachineCatalogDtos {

    private MachineCatalogDtos() {}

    public record BrandRequest(@NotBlank @Size(max = 60) String name) {}

    /** @param machineType WASHER, DRYER, or null when not known */
    public record ModelRequest(@NotBlank @Size(max = 100) String name, MachineType machineType) {}

    /** @param deviceCount devices using this model: it cannot be deleted while above 0 */
    public record ModelView(UUID id, UUID brandId, String name, MachineType machineType, long deviceCount) {}

    /** A brand with its models, sorted by name. */
    public record BrandView(UUID id, String name, List<ModelView> models) {}
}
