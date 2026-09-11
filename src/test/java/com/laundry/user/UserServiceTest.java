package com.laundry.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.laundry.auth.exception.InvalidCredentialsException;
import com.laundry.auth.repository.RefreshTokenRepository;
import com.laundry.user.dto.request.UpdateProfileRequest;
import com.laundry.user.dto.response.UserProfileResponse;
import com.laundry.user.entity.Role;
import com.laundry.user.entity.User;
import com.laundry.user.repository.UserRepository;
import com.laundry.user.service.UserService;

class UserServiceTest {

    private UserRepository userRepository;
    private RefreshTokenRepository refreshTokenRepository;
    private UserService userService;
    private User testUser;
    private UUID testUserId;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        var passwordEncoder = new BCryptPasswordEncoder();
        userService = new UserService(userRepository, refreshTokenRepository, passwordEncoder);
        
        testUserId = UUID.randomUUID();
        testUser = new User("Test User", "test@example.com", "+1234567890", 
            passwordEncoder.encode("Password123!"));
        testUser.addRole(new Role("CUSTOMER"));
        
        // Use reflection to set ID since constructor doesn't
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(testUser, testUserId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setSecurityContext(UUID userId) {
        var authentication = new UsernamePasswordAuthenticationToken(
            userId.toString(), null, 
            java.util.List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    @DisplayName("Should return current user profile when authenticated")
    void getCurrentUserProfile_ReturnsProfile_WhenAuthenticated() {
        setSecurityContext(testUserId);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        UserProfileResponse profile = userService.getCurrentUserProfile();

        assertThat(profile).isNotNull();
        assertThat(profile.email()).isEqualTo("test@example.com");
        assertThat(profile.fullName()).isEqualTo("Test User");
        assertThat(profile.roles()).containsExactly("CUSTOMER");
        assertThat(profile.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("Should update profile successfully")
    void updateCurrentUserProfile_UpdatesProfile_WhenValidRequest() {
        setSecurityContext(testUserId);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateProfileRequest request = new UpdateProfileRequest("Updated Name", "+9876543210");
        UserProfileResponse updated = userService.updateCurrentUserProfile(request);

        assertThat(updated.fullName()).isEqualTo("Updated Name");
        assertThat(updated.phone()).isEqualTo("+9876543210");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("Should change password when current password is correct")
    void changePassword_Success_WhenCurrentPasswordCorrect() {
        setSecurityContext(testUserId);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        userService.changePassword("Password123!", "NewPassword456!", "NewPassword456!");

        verify(userRepository).save(any(User.class));
        verify(refreshTokenRepository).revokeAllByUser(testUser);
    }

    @Test
    @DisplayName("Should throw exception when current password is incorrect")
    void changePassword_ThrowsException_WhenCurrentPasswordWrong() {
        setSecurityContext(testUserId);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        org.junit.jupiter.api.Assertions.assertThrows(InvalidCredentialsException.class, () -> 
            userService.changePassword("WrongPassword!", "NewPassword456!", "NewPassword456!")
        );
    }

    @Test
    @DisplayName("Should throw exception when passwords don't match")
    void changePassword_ThrowsException_WhenPasswordsDontMatch() {
        setSecurityContext(testUserId);
        when(userRepository.findById(testUserId)).thenReturn(Optional.of(testUser));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> 
            userService.changePassword("Password123!", "NewPassword456!", "DifferentPassword!")
        );
    }

    @Test
    @DisplayName("Should check if current user has role")
    void currentUserHasRole_ReturnsTrue_WhenUserHasRole() {
        setSecurityContext(testUserId);

        assertThat(userService.currentUserHasRole("CUSTOMER")).isTrue();
    }

    @Test
    @DisplayName("Should return false for role user doesn't have")
    void currentUserHasRole_ReturnsFalse_WhenUserDoesNotHaveRole() {
        setSecurityContext(testUserId);

        assertThat(userService.currentUserHasRole("ADMIN")).isFalse();
    }
}
