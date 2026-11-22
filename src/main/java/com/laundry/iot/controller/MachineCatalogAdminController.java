package com.laundry.iot.controller;

import com.laundry.iot.dto.MachineCatalogDtos.BrandRequest;
import com.laundry.iot.dto.MachineCatalogDtos.BrandView;
import com.laundry.iot.dto.MachineCatalogDtos.ModelRequest;
import com.laundry.iot.dto.MachineCatalogDtos.ModelView;
import com.laundry.iot.service.MachineCatalogAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The machine brand/model catalog (V63). Admin only ({@code SecurityConfig}). */
@RestController
@RequestMapping("/api/admin/machine-brands")
@RequiredArgsConstructor
@Tag(name = "Machine brands and models (Admin)", description = "Catalog used when approving a device")
public class MachineCatalogAdminController {

    private final MachineCatalogAdminService catalog;

    @GetMapping
    @Operation(summary = "Every brand with its models")
    public List<BrandView> list() {
        return catalog.list();
    }

    @PostMapping
    @Operation(summary = "Add a brand")
    public BrandView createBrand(@Valid @RequestBody BrandRequest request) {
        return catalog.createBrand(request);
    }

    @PutMapping("/{brandId}")
    @Operation(summary = "Rename a brand")
    public BrandView renameBrand(@PathVariable UUID brandId, @Valid @RequestBody BrandRequest request) {
        return catalog.renameBrand(brandId, request);
    }

    @DeleteMapping("/{brandId}")
    @Operation(summary = "Delete a brand that has no models")
    public ResponseEntity<Void> deleteBrand(@PathVariable UUID brandId) {
        catalog.deleteBrand(brandId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{brandId}/models")
    @Operation(summary = "Add a model to a brand")
    public ModelView createModel(@PathVariable UUID brandId, @Valid @RequestBody ModelRequest request) {
        return catalog.createModel(brandId, request);
    }

    @PutMapping("/models/{modelId}")
    @Operation(summary = "Rename a model or change its machine type")
    public ModelView updateModel(@PathVariable UUID modelId, @Valid @RequestBody ModelRequest request) {
        return catalog.updateModel(modelId, request);
    }

    @DeleteMapping("/models/{modelId}")
    @Operation(summary = "Delete a model no device uses")
    public ResponseEntity<Void> deleteModel(@PathVariable UUID modelId) {
        catalog.deleteModel(modelId);
        return ResponseEntity.noContent().build();
    }
}
