package com.laundry.branch.controller;

import com.laundry.branch.dto.BranchDto;
import com.laundry.branch.dto.UpdateBranchRequest;
import com.laundry.branch.service.BranchService;
import com.laundry.branch.service.StoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one store this server runs (single-store mode). Customers and the store TV read it without
 * logging in; the Owner edits its name, address, phone and map. There is no create or delete:
 * the store always exists ({@link StoreService}).
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Store", description = "The single store this server runs")
public class StoreController {

    private final StoreService store;
    private final BranchService branches;

    @GetMapping("/api/store")
    @Operation(summary = "The store: id, name, address, phone, map")
    public BranchDto store() {
        return branches.toDto(store.current());
    }

    @GetMapping("/api/admin/store")
    @Operation(summary = "The store, for the settings page")
    public BranchDto adminStore() {
        return branches.toDto(store.current());
    }

    @PutMapping("/api/admin/store")
    @Operation(summary = "Edit the store's name, address, phone and map (Owner)")
    public BranchDto update(@Valid @RequestBody UpdateBranchRequest request) {
        request.setStatus(null);   // the only store cannot be switched off
        return branches.updateBranch(store.id(), request);
    }
}
