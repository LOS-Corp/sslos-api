package com.laundry.machine.controller;

import com.laundry.machine.dto.PriceProfileDtos.PriceProfileRequest;
import com.laundry.machine.dto.PriceProfileDtos.PriceProfileView;
import com.laundry.machine.service.PriceProfileService;
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

/** Price profiles of machines (V64). Owner and admin ({@code SecurityConfig}). */
@RestController
@RequestMapping("/api/admin/price-profiles")
@RequiredArgsConstructor
@Tag(name = "Price profiles (Admin)", description = "How a machine is priced: base price + price per kg")
public class PriceProfileAdminController {

    private final PriceProfileService profiles;

    @GetMapping
    @Operation(summary = "Every price profile, with how many machines use it")
    public List<PriceProfileView> list() {
        return profiles.list();
    }

    @PostMapping
    @Operation(summary = "Add a price profile")
    public PriceProfileView create(@Valid @RequestBody PriceProfileRequest request) {
        return profiles.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Change a price profile (applies to new bookings)")
    public PriceProfileView update(@PathVariable UUID id, @Valid @RequestBody PriceProfileRequest request) {
        return profiles.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a price profile no machine uses")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        profiles.delete(id);
        return ResponseEntity.noContent().build();
    }
}
