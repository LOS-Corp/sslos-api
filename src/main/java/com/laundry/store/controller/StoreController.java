package com.laundry.store.controller;

import com.laundry.shared.security.CurrentUser;
import com.laundry.store.dto.StoreDto;
import com.laundry.store.dto.UpdateStoreRequest;
import com.laundry.store.service.StoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The store this server runs: customers and the store TV read it without logging in; the Owner edits it. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Store", description = "The store this server runs")
public class StoreController {

    private final StoreService store;

    @GetMapping("/api/store")
    @Operation(summary = "Name, address, phone, map and booking timezone of the store")
    public StoreDto store() {
        return store.get();
    }

    @GetMapping("/api/admin/store")
    @Operation(summary = "The store, for the settings page")
    public StoreDto adminStore() {
        return store.get();
    }

    @PutMapping("/api/admin/store")
    @Operation(summary = "Edit the store's name, address, phone and map (Owner)")
    public StoreDto update(@Valid @RequestBody UpdateStoreRequest request, CurrentUser owner) {
        return store.update(request, owner);
    }
}
