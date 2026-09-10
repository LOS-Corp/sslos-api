package com.laundry.auth;

public record LogoutResponse(String message) {

    public static LogoutResponse success() {
        return new LogoutResponse("Logged out successfully");
    }
}
