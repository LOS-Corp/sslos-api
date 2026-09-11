package com.laundry.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.laundry.user.dto.request.UpdateProfileRequest;
import com.laundry.user.dto.response.UserProfileResponse;
import com.laundry.user.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * REST Controller for user profile management.
 * Handles user profile retrieval and updates.
 */
@RestController
@RequestMapping("/api/v1/users")
@Validated
@Tag(name = "User Profile", description = "Endpoints for managing user profiles")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Get current user's profile.
     * Requires authentication.
     * 
     * @return UserProfileResponse with current user details
     */
    @GetMapping("/me")
    @Operation(summary = "Get current user profile")
    public ResponseEntity<UserProfileResponse> getCurrentUserProfile() {
        return ResponseEntity.ok(userService.getCurrentUserProfile());
    }

    /**
     * Update current user's profile.
     * Only allows updating fullName and phone.
     * Password changes must use /api/auth/change-password.
     * 
     * @param request UpdateProfileRequest with new values
     * @return Updated UserProfileResponse
     */
    @PutMapping("/me")
    @Operation(summary = "Update current user profile")
    public ResponseEntity<UserProfileResponse> updateCurrentUserProfile(
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(userService.updateCurrentUserProfile(request));
    }
}
