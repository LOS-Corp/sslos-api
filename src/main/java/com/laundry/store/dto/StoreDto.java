package com.laundry.store.dto;

import java.time.OffsetDateTime;

/** The store as the API shows it. {@code timezone} is null until the Owner configures booking. */
public record StoreDto(
        String name,
        String address,
        String phone,
        String mapEmbedUrl,
        String timezone,
        OffsetDateTime updatedAt
) {}
