package com.laundry.auth;

import com.laundry.auth.dto.request.ResetPasswordRequest;
import com.laundry.auth.entity.OtpCode;
import com.laundry.auth.exception.InvalidTokenException;
import com.laundry.auth.repository.OtpCodeRepository;
import com.laundry.auth.service.AuthService;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import com.laundry.user.entity.User;
import com.laundry.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** FR-AUTH-01: the 6-digit reset OTP cannot be guessed. */
@IntegrationTest
class OtpLockoutIT {

    private static final String NEW_PASSWORD = "NewSecret#123";

    @Autowired private AuthService auth;
    @Autowired private UserRepository users;
    @Autowired private OtpCodeRepository otps;
    @Autowired private JdbcTemplate jdbc;

    private User user;
    private String code;

    @BeforeEach
    void setUp() {
        UUID id = Fixtures.customer(jdbc);
        user = users.findById(id).orElseThrow();
        code = "654321";
        otps.saveAndFlush(new OtpCode(user, code, OffsetDateTime.now().plusMinutes(5)));
    }

    @Test
    @DisplayName("FR-AUTH-01: five wrong OTPs lock the reset, even for the right OTP")
    void locksAfterFiveWrongCodes() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> reset(user.getEmail(), "000000")).isInstanceOf(InvalidTokenException.class);
        }

        assertThatThrownBy(() -> reset(user.getEmail(), code))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));
    }

    @Test
    @DisplayName("An unknown email is answered and throttled like a real one")
    void unknownEmailLooksTheSame() {
        String unknown = "nobody-" + UUID.randomUUID() + "@test.vn";
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> reset(unknown, "000000")).isInstanceOf(InvalidTokenException.class);
        }
        assertThatThrownBy(() -> reset(unknown, "000000"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).code()).isEqualTo(ErrorCode.TOO_MANY_ATTEMPTS));
    }

    @Test
    @DisplayName("The right OTP works and clears the failures")
    void rightCodeResets() {
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> reset(user.getEmail(), "000000")).isInstanceOf(InvalidTokenException.class);
        }

        reset(user.getEmail(), code);

        assertThatThrownBy(() -> reset(user.getEmail(), code)).as("the OTP is single use").isInstanceOf(InvalidTokenException.class);
        otps.saveAndFlush(new OtpCode(user, "123456", OffsetDateTime.now().plusMinutes(5)));
        reset(user.getEmail(), "123456");
    }

    private void reset(String email, String otp) {
        auth.resetPassword(new ResetPasswordRequest(email, otp, NEW_PASSWORD, NEW_PASSWORD));
    }
}
