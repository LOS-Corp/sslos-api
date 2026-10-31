package com.laundry.auth;

import com.laundry.auth.dto.request.LoginRequest;
import com.laundry.auth.exception.InvalidCredentialsException;
import com.laundry.auth.service.AuthService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.support.IntegrationTest;
import com.laundry.user.entity.User;
import com.laundry.user.repository.RoleRepository;
import com.laundry.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-AUTH-01 and UC-01 exceptions 3a/3b. */
@IntegrationTest
class LoginLockoutTest {

    private static final String PASSWORD = "Secret#123";

    @Autowired private AuthService authService;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;

    private String email;

    @BeforeEach
    void setUp() {
        email = "login-" + UUID.randomUUID() + "@test.vn";
        User user = new User("Khách", email, "+849" + String.format("%08d", (long) (Math.random() * 1e8)),
                passwordEncoder.encode(PASSWORD));
        user.addRole(roles.findByName("CUSTOMER").orElseThrow());
        users.saveAndFlush(user);
    }

    @Test
    @DisplayName("FR-AUTH-01: five wrong passwords lock the login, even for the right password")
    void locksAfterRepeatedFailures() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(email, "wrong")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThatThrownBy(() -> authService.login(new LoginRequest(email, PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));
    }

    @Test
    @DisplayName("A successful login resets the failure counter")
    void successResetsCounter() {
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(email, "wrong")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(authService.login(new LoginRequest(email, PASSWORD)).accessToken()).isNotBlank();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest(email, "wrong")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(authService.login(new LoginRequest(email, PASSWORD)).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("UC-01 3b: a locked account with the right password gets MSG03")
    void lockedAccountGetsMsg03() {
        User user = users.findByEmail(email).orElseThrow();
        user.setStatus("LOCKED");
        users.saveAndFlush(user);

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.ACCOUNT_LOCKED);
                    assertThat(((BusinessException) e).code().messageCode()).isEqualTo("MSG03");
                });
    }
}
