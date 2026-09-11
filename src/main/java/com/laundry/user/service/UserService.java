package com.laundry.user.service;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.laundry.auth.exception.InvalidCredentialsException;
import com.laundry.auth.repository.RefreshTokenRepository;
import com.laundry.user.dto.request.UpdateProfileRequest;
import com.laundry.user.dto.response.UserProfileResponse;
import com.laundry.user.entity.User;
import com.laundry.user.repository.UserRepository;

/**
 * Service for user profile management operations.
 * Handles profile retrieval and updates with proper ownership validation.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Get the currently authenticated user's ID from SecurityContext.
     * 
     * @return UUID of the authenticated user
     * @throws IllegalStateException if no authenticated user found
     */
    public UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new IllegalStateException("No authenticated user found");
        }
        String principal = authentication.getName();
        try {
            return UUID.fromString(principal);
        } catch (IllegalArgumentException e) {
            // If principal is email, lookup user
            return userRepository.findByEmail(principal)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found"));
        }
    }

    /**
     * Get the currently authenticated user entity.
     * 
     * @return User entity
     * @throws IllegalStateException if user not found
     */
    public User getCurrentUser() {
        return userRepository.findById(getCurrentUserId())
            .orElseThrow(() -> new IllegalStateException("User not found"));
    }

    /**
     * Get profile of the currently authenticated user.
     * 
     * @return UserProfileResponse with user details
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getCurrentUserProfile() {
        User user = getCurrentUser();
        return UserProfileResponse.from(user);
    }

    /**
     * Get profile by user ID.
     * Implements ownership check - users can only view their own profile.
     * 
     * @param userId User ID to lookup
     * @return UserProfileResponse
     * @throws org.springframework.security.access.AccessDeniedException if not owner
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfileById(UUID userId) {
        UUID currentUserId = getCurrentUserId();
        if (!currentUserId.equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException(
                "You can only view your own profile"
            );
        }
        return userRepository.findById(userId)
            .map(UserProfileResponse::from)
            .orElseThrow(() -> new IllegalStateException("User not found"));
    }

    /**
     * Update profile of the currently authenticated user.
     * Only allows updating fullName and phone.
     * 
     * @param request UpdateProfileRequest with new values
     * @return Updated UserProfileResponse
     */
    @Transactional
    public UserProfileResponse updateCurrentUserProfile(UpdateProfileRequest request) {
        User user = getCurrentUser();
        
        user.setFullName(request.fullName());
        user.setPhone(request.phone());
        
        User savedUser = userRepository.save(user);
        return UserProfileResponse.from(savedUser);
    }

    /**
     * Change password for the currently authenticated user.
     * Verifies current password before allowing change.
     * Revokes all refresh tokens for security.
     * 
     * @param currentPassword Current password for verification
     * @param newPassword New password to set
     * @param confirmPassword Password confirmation
     * @throws InvalidCredentialsException if current password is incorrect
     * @throws IllegalArgumentException if passwords don't match
     */
    @Transactional
    public void changePassword(String currentPassword, String newPassword, String confirmPassword) {
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("Password confirmation does not match");
        }

        User user = getCurrentUser();
        
        // Verify current password
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        // Update password
        user.changePassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Revoke all refresh tokens for security
        refreshTokenRepository.revokeAllByUser(user);
    }

    /**
     * Check if current user has a specific role.
     * 
     * @param roleName Role name to check (e.g., "CUSTOMER", "ADMIN")
     * @return true if user has the role
     */
    public boolean currentUserHasRole(String roleName) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(auth -> auth.getAuthority().equals("ROLE_" + roleName));
    }

    /**
     * Get user by ID (internal use for ownership checks).
     * 
     * @param userId User ID
     * @return User entity
     */
    @Transactional(readOnly = true)
    public User getUserById(UUID userId) {
        return userRepository.findById(userId).orElse(null);
    }
}
