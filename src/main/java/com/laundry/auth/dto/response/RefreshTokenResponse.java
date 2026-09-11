package com.laundry.auth.dto.response;

/**
 * Refresh token response containing new access and refresh tokens.
 */
public record RefreshTokenResponse(
    String accessToken,
    String refreshToken,
    String tokenType
) {
    public RefreshTokenResponse(String accessToken, String refreshToken) {
        this(accessToken, refreshToken, "Bearer");
    }
}
