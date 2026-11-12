package com.laundry.store.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Owner edits the store's contact details. Null leaves a field as it is; an empty string clears it. */
public record UpdateStoreRequest(
        @Size(min = 2, max = 120, message = "Store name must be between 2 and 120 characters") String name,
        @Size(max = 255, message = "Address cannot exceed 255 characters") String address,
        @Size(max = 20, message = "Phone cannot exceed 20 characters") String phone,
        @Size(max = 500, message = "Map embed URL cannot exceed 500 characters")
        @Pattern(regexp = "^(https?://.+)?$", message = "Map embed URL must be a valid http(s) URL") String mapEmbedUrl
) {}
