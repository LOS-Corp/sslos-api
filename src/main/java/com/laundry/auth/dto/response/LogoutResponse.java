package com.laundry.auth.dto.response;

/**
 * Logout response.
 */
public record LogoutResponse(String message) {
    public static LogoutResponse success() {
        return new LogoutResponse("Logged out successfully");
    }
}
