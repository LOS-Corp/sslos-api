package com.laundry.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating user profile.
 * Only allows updating fields that users are permitted to change themselves.
 * 
 * Note: Password, roles, and status CANNOT be updated through this endpoint.
 * Use /api/auth/change-password for password changes.
 */
public record UpdateProfileRequest(
    @NotBlank(message = "Full name is required")
    @Size(max = 120, message = "Full name must not exceed 120 characters")
    String fullName,

    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "Phone must use E.164 format")
    String phone
) {}
