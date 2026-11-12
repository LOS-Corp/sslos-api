package com.laundry.store.service;

import com.laundry.audit.service.AuditAction;
import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditEntry;
import com.laundry.shared.security.CurrentUser;
import com.laundry.store.dto.StoreDto;
import com.laundry.store.dto.UpdateStoreRequest;
import com.laundry.store.entity.StoreSettings;
import com.laundry.store.repository.StoreSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one store this server runs. V58 creates its row; a database where the row is somehow missing
 * gets a default one on first use, so the store always exists.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StoreService {

    static final String DEFAULT_NAME = "Cửa hàng LaundryOS";

    private final StoreSettingsRepository repository;
    private final AuditService audit;

    @Transactional
    public StoreSettings settings() {
        return repository.findById(StoreSettings.ID).orElseGet(() -> {
            log.info("No store row yet: creating the default store");
            return repository.save(StoreSettings.builder().name(DEFAULT_NAME).build());
        });
    }

    @Transactional
    public StoreDto get() {
        return toDto(settings());
    }

    @Transactional
    public StoreDto update(UpdateStoreRequest request, CurrentUser owner) {
        StoreSettings store = settings();
        StoreDto before = toDto(store);
        if (request.name() != null && !request.name().isBlank()) store.setName(request.name().trim());
        if (request.address() != null) store.setAddress(blankToNull(request.address()));
        if (request.phone() != null) store.setPhone(blankToNull(request.phone()));
        if (request.mapEmbedUrl() != null) store.setMapEmbedUrl(blankToNull(request.mapEmbedUrl()));
        StoreDto after = toDto(repository.save(store));
        audit.record(AuditEntry.of(AuditAction.STORE_UPDATED, "STORE", StoreSettings.ID, owner)
                .withChange(contact(before), contact(after)));
        return after;
    }

    private static java.util.Map<String, String> contact(StoreDto store) {
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("name", store.name());
        fields.put("address", store.address());
        fields.put("phone", store.phone());
        fields.put("mapEmbedUrl", store.mapEmbedUrl());
        return fields;
    }

    public static StoreDto toDto(StoreSettings store) {
        return new StoreDto(store.getName(), store.getAddress(), store.getPhone(), store.getMapEmbedUrl(),
                store.getTimezone(), store.getUpdatedAt());
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
