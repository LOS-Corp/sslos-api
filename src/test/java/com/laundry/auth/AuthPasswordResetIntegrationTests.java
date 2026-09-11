package com.laundry.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.user.Role;
import com.laundry.user.RoleRepository;
import com.laundry.user.User;
import com.laundry.user.UserRepository;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthPasswordResetIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;

    @BeforeEach
    void setUp() {
        passwordResetTokenRepository.deleteAll();
        refreshTokenRepository.deleteAll();

        Role role = roleRepository.findByName("CUSTOMER")
            .orElseGet(() -> roleRepository.save(new Role("CUSTOMER")));

        testUser = userRepository.findByEmail("reset-user@example.com")
            .orElseGet(() -> {
                User newUser = new User("Reset User", "reset-user@example.com", "+84901234567", passwordEncoder.encode("OldPassword!1"));
                newUser.addRole(role);
                return userRepository.save(newUser);
            });

        // Ensure password is reset to initial state for each test
        testUser.changePassword(passwordEncoder.encode("OldPassword!1"));
        userRepository.save(testUser);
    }

    @Test
    void forgotPassword_withValidEmail_generatesResetTokenAndReturnsSuccess() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest("reset-user@example.com");

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("If your email is registered in our system, you will receive password reset instructions."));

        var tokens = passwordResetTokenRepository.findAll();
        assertThat(tokens).hasSize(1);
        PasswordResetToken token = tokens.getFirst();
        assertThat(token.getUser().getId()).isEqualTo(testUser.getId());
        assertThat(token.isValid()).isTrue();
        assertThat(token.isUsed()).isFalse();
    }

    @Test
    void forgotPassword_withNonExistentEmail_returnsGenericSuccessWithoutGeneratingToken() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest("nonexistent@example.com");

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("If your email is registered in our system, you will receive password reset instructions."));

        assertThat(passwordResetTokenRepository.findAll()).isEmpty();
    }

    @Test
    void resetPassword_withValidToken_resetsPasswordAndRevokesRefreshTokens() throws Exception {
        // Create an existing refresh token for the user
        RefreshToken refreshToken = new RefreshToken(testUser, "sample-refresh-token", OffsetDateTime.now().plusDays(7));
        refreshTokenRepository.save(refreshToken);

        // Create a valid password reset token
        PasswordResetToken resetToken = new PasswordResetToken(testUser, "valid-reset-token-123", OffsetDateTime.now().plusMinutes(15));
        passwordResetTokenRepository.save(resetToken);

        ResetPasswordRequest request = new ResetPasswordRequest("valid-reset-token-123", "NewSecret@2026", "NewSecret@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Password has been reset successfully"));

        // Verify token marked used
        PasswordResetToken updatedToken = passwordResetTokenRepository.findByToken("valid-reset-token-123").orElseThrow();
        assertThat(updatedToken.isUsed()).isTrue();
        assertThat(updatedToken.isValid()).isFalse();

        // Verify old refresh token is revoked
        RefreshToken updatedRefreshToken = refreshTokenRepository.findByToken("sample-refresh-token").orElseThrow();
        assertThat(updatedRefreshToken.isRevoked()).isTrue();

        // Verify user can login with new password
        LoginRequest newLogin = new LoginRequest("reset-user@example.com", "NewSecret@2026");
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(newLogin)))
            .andExpect(status().isOk());

        // Verify old password fails
        LoginRequest oldLogin = new LoginRequest("reset-user@example.com", "OldPassword!1");
        mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(oldLogin)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void resetPassword_withMismatchedConfirmPassword_returnsBadRequest() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest("some-token", "NewSecret@2026", "DifferentPassword@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Password confirmation does not match"));
    }

    @Test
    void resetPassword_withWeakPassword_returnsBadRequest() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest("some-token", "weak", "weak");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.newPassword").exists());
    }

    @Test
    void resetPassword_withExpiredToken_returnsUnauthorized() throws Exception {
        PasswordResetToken expiredToken = new PasswordResetToken(testUser, "expired-token", OffsetDateTime.now().minusMinutes(5));
        passwordResetTokenRepository.save(expiredToken);

        ResetPasswordRequest request = new ResetPasswordRequest("expired-token", "NewSecret@2026", "NewSecret@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Password reset token is invalid or expired"));
    }

    @Test
    void resetPassword_withAlreadyUsedToken_returnsUnauthorized() throws Exception {
        PasswordResetToken usedToken = new PasswordResetToken(testUser, "used-token", OffsetDateTime.now().plusMinutes(15));
        usedToken.markAsUsed();
        passwordResetTokenRepository.save(usedToken);

        ResetPasswordRequest request = new ResetPasswordRequest("used-token", "NewSecret@2026", "NewSecret@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Password reset token is invalid or expired"));
    }

    @Test
    void resetPassword_withNonExistentToken_returnsUnauthorized() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest("non-existent-token", "NewSecret@2026", "NewSecret@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Password reset token is invalid or expired"));
    }
}
