package com.laundry.auth.dto.response;

import com.laundry.user.entity.User;

import java.util.UUID;

/**
 * Response DTO for authenticated user information.
 * Used in login and registration responses.
 * 
 * Note: This is used for all user types (CUSTOMER, STAFF, OWNER, ADMIN).
 */
public record AuthenticatedUserResponse(
    UUID userId,
    String fullName,
    String email,
    String phone
) {
    /**
     * Factory method to create from User entity.
     */
    public static AuthenticatedUserResponse from(User user) {
        return new AuthenticatedUserResponse(
            user.getId(),
            user.getFullName(),
            user.getEmail(),
            user.getPhone()
        );
    }
}
