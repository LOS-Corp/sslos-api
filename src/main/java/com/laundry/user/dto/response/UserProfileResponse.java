package com.laundry.user.dto.response;

import com.laundry.user.entity.Role;
import com.laundry.user.entity.User;

import java.util.List;
import java.util.UUID;

/**
 * Response DTO for user profile information.
 * Exposes user details including roles and status for authenticated users.
 * 
 * @param id        User's unique identifier
 * @param email     User's email address
 * @param fullName  User's full name
 * @param phone     User's phone number (E.164 format)
 * @param roles     List of role names assigned to user
 * @param status    User account status (ACTIVE, INACTIVE, LOCKED, SUSPENDED)
 */
public record UserProfileResponse(
    UUID id,
    String email,
    String fullName,
    String phone,
    List<String> roles,
    String status
) {
    /**
     * Factory method to create UserProfileResponse from User entity.
     * 
     * @param user The User entity
     * @return UserProfileResponse instance
     */
    public static UserProfileResponse from(User user) {
        List<String> roleNames = user.getRoles().stream()
            .map(Role::getName)
            .sorted()
            .toList();
        return new UserProfileResponse(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            user.getPhone(),
            roleNames,
            user.getStatus()
        );
    }
}
