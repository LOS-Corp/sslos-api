package com.laundry.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.mockito.Mockito.mock;

class AuthServiceLogoutTests {

    @Test
    void logoutClearsSecurityContextAndReturnsSuccessMessage() {
        var userRepo = mock(com.laundry.user.UserRepository.class);
        var tokenRepo = mock(RefreshTokenRepository.class);
        AuthService authService = new AuthService(userRepo, null, tokenRepo, null, null, null, null, 604800L, 900L);
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("user", "credentials"));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();

        LogoutResponse response = authService.logout();

        assertThat(response).isNotNull();
        assertThat(response.message()).isEqualTo("Logged out successfully");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
