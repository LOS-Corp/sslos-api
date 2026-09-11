package com.laundry.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.laundry.auth.dto.response.LogoutResponse;
import com.laundry.auth.repository.OtpCodeRepository;
import com.laundry.auth.repository.RefreshTokenRepository;
import com.laundry.auth.service.AuthService;

import static org.mockito.Mockito.mock;

class AuthServiceLogoutTests {

    @Test
    void logoutClearsSecurityContextAndReturnsSuccessMessage() {
        var userRepo = mock(com.laundry.user.repository.UserRepository.class);
        var roleRepo = mock(com.laundry.user.repository.RoleRepository.class);
        var tokenRepo = mock(RefreshTokenRepository.class);
        var otpRepo = mock(OtpCodeRepository.class);
        var emailService = mock(com.laundry.shared.EmailService.class);

        AuthService authService = new AuthService(
            userRepo, roleRepo, tokenRepo, otpRepo, null, null, emailService, 604800L
        );
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("user", "credentials"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();

        LogoutResponse response = authService.logout();

        assertThat(response).isNotNull();
        assertThat(response.message()).isEqualTo("Logged out successfully");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
