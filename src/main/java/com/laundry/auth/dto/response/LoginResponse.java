package com.laundry.auth.dto.response;

/**
 * Login response containing JWT tokens and user information.
 */
public record LoginResponse(
    String accessToken,
    String refreshToken,
    AuthenticatedUserResponse user,
    String role
) {}
