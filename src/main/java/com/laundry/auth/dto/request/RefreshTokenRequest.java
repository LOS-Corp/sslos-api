package com.laundry.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Refresh token request DTO.
 */
public record RefreshTokenRequest(
    @NotBlank(message = "Refresh token is required")
    String refreshToken
) {}
