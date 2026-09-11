package com.laundry.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.laundry.auth.dto.request.ForgotPasswordRequest;
import com.laundry.auth.dto.request.LoginRequest;
import com.laundry.auth.dto.request.ResetPasswordRequest;
import com.laundry.auth.entity.OtpCode;
import com.laundry.auth.entity.RefreshToken;
import com.laundry.auth.repository.OtpCodeRepository;
import com.laundry.auth.repository.RefreshTokenRepository;
import com.laundry.user.entity.Role;
import com.laundry.user.entity.User;
import com.laundry.user.repository.RoleRepository;
import com.laundry.user.repository.UserRepository;

@SpringBootTest
@AutoConfigureMockMvc
class AuthPasswordResetIntegrationTests {

    private static final Pattern OTP_PATTERN = Pattern.compile("\\d{6}");

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
    private OtpCodeRepository otpCodeRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;

    @BeforeEach
    void setUp() {
        otpCodeRepository.deleteAll();
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
    void forgotPassword_withValidEmail_generatesOtpAndReturnsSuccess() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest("reset-user@example.com");

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("If your email is registered in our system, you will receive password reset instructions."));

        var otpCodes = otpCodeRepository.findAll();
        assertThat(otpCodes).hasSize(1);
        OtpCode otp = otpCodes.getFirst();
        assertThat(otp.getUser().getId()).isEqualTo(testUser.getId());
        assertThat(otp.isValid()).isTrue();
        assertThat(otp.isUsed()).isFalse();
        assertThat(otp.getCode()).matches(OTP_PATTERN);
    }

    @Test
    void forgotPassword_withNonExistentEmail_returnsGenericSuccessWithoutGeneratingOtp() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest("nonexistent@example.com");

        mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("If your email is registered in our system, you will receive password reset instructions."));

        assertThat(otpCodeRepository.findAll()).isEmpty();
    }

    @Test
    void resetPassword_withValidOtp_resetsPasswordAndRevokesRefreshTokens() throws Exception {
        // Create an existing refresh token for the user
        RefreshToken refreshToken = new RefreshToken(testUser, "sample-refresh-token", OffsetDateTime.now().plusDays(7));
        refreshTokenRepository.save(refreshToken);

        // Create a valid OTP code
        OtpCode otp = new OtpCode(testUser, "123456", OffsetDateTime.now().plusMinutes(5));
        otpCodeRepository.save(otp);

        ResetPasswordRequest request = new ResetPasswordRequest("reset-user@example.com", "123456", "NewSecret@2026", "NewSecret@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("Password has been reset successfully"));

        // Verify OTP marked used
        OtpCode updatedOtp = otpCodeRepository.findById(otp.getId()).orElseThrow();
        assertThat(updatedOtp.isUsed()).isTrue();
        assertThat(updatedOtp.isValid()).isFalse();

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
        ResetPasswordRequest request = new ResetPasswordRequest("reset-user@example.com", "123456", "NewSecret@2026", "DifferentPassword@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Password confirmation does not match"));
    }

    @Test
    void resetPassword_withWeakPassword_returnsBadRequest() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest("reset-user@example.com", "123456", "weak", "weak");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fields.newPassword").exists());
    }

    @Test
    void resetPassword_withExpiredOtp_returnsUnauthorized() throws Exception {
        OtpCode expiredOtp = new OtpCode(testUser, "654321", OffsetDateTime.now().minusMinutes(5));
        otpCodeRepository.save(expiredOtp);

        ResetPasswordRequest request = new ResetPasswordRequest("reset-user@example.com", "654321", "NewSecret@2026", "NewSecret@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid OTP or OTP has expired"));
    }

    @Test
    void resetPassword_withAlreadyUsedOtp_returnsUnauthorized() throws Exception {
        OtpCode usedOtp = new OtpCode(testUser, "111222", OffsetDateTime.now().plusMinutes(5));
        usedOtp.markAsUsed();
        otpCodeRepository.save(usedOtp);

        ResetPasswordRequest request = new ResetPasswordRequest("reset-user@example.com", "111222", "NewSecret@2026", "NewSecret@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid OTP or OTP has expired"));
    }

    @Test
    void resetPassword_withInvalidOtp_returnsUnauthorized() throws Exception {
        ResetPasswordRequest request = new ResetPasswordRequest("reset-user@example.com", "999999", "NewSecret@2026", "NewSecret@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid OTP or OTP has expired"));
    }

    @Test
    void resetPassword_withWrongEmail_returnsUnauthorized() throws Exception {
        OtpCode otp = new OtpCode(testUser, "555666", OffsetDateTime.now().plusMinutes(5));
        otpCodeRepository.save(otp);

        ResetPasswordRequest request = new ResetPasswordRequest("wrong-email@example.com", "555666", "NewSecret@2026", "NewSecret@2026");

        mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid OTP or email"));
    }
}
