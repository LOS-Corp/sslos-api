package com.laundry.auth;

import com.laundry.common.Tokens;
import com.laundry.common.error.BusinessException;
import com.laundry.common.error.ErrorCode;
import com.laundry.notification.EmailSender;
import com.laundry.security.JwtService;
import com.laundry.security.Role;
import com.laundry.user.User;
import com.laundry.user.UserDtos.UserView;
import com.laundry.user.UserRepository;
import com.laundry.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository users;
    private final UserService userService;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordResetTokenRepository resetTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final EmailSender email;
    private final LoginEventService loginEvents;
    private final Clock clock;

    @Value("${app.jwt.refresh-expiration-seconds}")
    private long refreshSeconds;
    @Value("${app.auth.reset-token-expiration-seconds}")
    private long resetSeconds;
    @Value("${app.auth.reset-url}")
    private String resetUrl;

    /** Một mật khẩu băm sẵn để so khi email không tồn tại: thời gian trả lời như nhau, không lộ email nào có. */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BVNCj5iNXfFvbWBzQuLhAI9hpLBi";

    public record AuthTokens(String accessToken, String refreshToken, long expiresIn, String tokenType, UserView user) {
    }

    @Transactional
    public UserView register(String fullName, String email, String phone, String password) {
        return UserView.of(userService.create(fullName, email, phone, password, EnumSet.of(Role.CUSTOMER)));
    }

    @Transactional
    public AuthTokens login(String rawEmail, String password, String userAgent, String ip) {
        Optional<User> found = users.findByEmail(rawEmail.trim());
        if (found.isEmpty()) {
            passwordEncoder.matches(password, DUMMY_HASH);
            loginEvents.record(null, rawEmail, LoginEventService.Failure.UNKNOWN_EMAIL, ip, userAgent);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        User user = found.get();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            loginEvents.record(user.getId(), user.getEmail(), LoginEventService.Failure.WRONG_PASSWORD, ip, userAgent);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.isActive()) {
            loginEvents.record(user.getId(), user.getEmail(), LoginEventService.Failure.ACCOUNT_LOCKED, ip, userAgent);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        user.setLastLoginAt(clock.instant());
        AuthTokens tokens = issue(user, UUID.randomUUID(), userAgent, ip);
        loginEvents.record(user.getId(), user.getEmail(), null, ip, userAgent);
        return tokens;
    }

    /** Xoay vòng: token cũ bị thu hồi; dùng lại một token đã thu hồi = bị lộ, thu hồi cả chuỗi. */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthTokens refresh(String rawToken, String userAgent, String ip) {
        RefreshToken token = refreshTokens.findByTokenHash(Tokens.sha256Hex(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        Instant now = clock.instant();
        if (token.getRevokedAt() != null) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; family revoked", token.getUserId());
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        if (token.getExpiresAt().isBefore(now)) throw new BusinessException(ErrorCode.INVALID_TOKEN);
        User user = users.findById(token.getUserId()).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        if (!user.isActive()) throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        AuthTokens next = issue(user, token.getFamilyId(), userAgent, ip);
        token.setRevokedAt(now);
        refreshTokens.findByTokenHash(Tokens.sha256Hex(next.refreshToken())).ifPresent(n -> token.setReplacedBy(n.getId()));
        return next;
    }

    @Transactional
    public void logout(UUID userId) {
        refreshTokens.revokeAll(userId, clock.instant());
    }

    /** Luôn trả lời giống nhau, không cho biết email có tồn tại hay không. */
    @Transactional
    public void forgotPassword(String rawEmail) {
        users.findByEmail(rawEmail.trim()).filter(User::isActive).ifPresent(user -> {
            Instant now = clock.instant();
            resetTokens.invalidateAll(user.getId(), now);
            String raw = Tokens.random(32);
            PasswordResetToken t = new PasswordResetToken();
            t.setUserId(user.getId());
            t.setTokenHash(Tokens.sha256Hex(raw));
            t.setExpiresAt(now.plusSeconds(resetSeconds));
            resetTokens.save(t);
            email.sendAsync(user.getEmail(), "LaundryOS - Đặt lại mật khẩu",
                    "Chào " + user.getFullName() + ",\n\nMở liên kết sau để đặt lại mật khẩu (hết hạn sau "
                            + resetSeconds / 60 + " phút):\n" + resetUrl + "?token=" + raw
                            + "\n\nNếu bạn không yêu cầu, hãy bỏ qua email này.");
        });
    }

    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken t = resetTokens.findByTokenHash(Tokens.sha256Hex(rawToken))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        Instant now = clock.instant();
        if (t.getUsedAt() != null || t.getExpiresAt().isBefore(now)) throw new BusinessException(ErrorCode.INVALID_TOKEN);
        User user = users.findById(t.getUserId()).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        t.setUsedAt(now);
        refreshTokens.revokeAll(user.getId(), now);
    }

    @Transactional
    public void changePassword(UUID userId, String current, String next) {
        User user = userService.get(userId);
        if (!passwordEncoder.matches(current, user.getPasswordHash())) throw new BusinessException(ErrorCode.WRONG_PASSWORD);
        user.setPasswordHash(passwordEncoder.encode(next));
        refreshTokens.revokeAll(userId, clock.instant());
    }

    private AuthTokens issue(User user, UUID familyId, String userAgent, String ip) {
        String raw = Tokens.random(32);
        RefreshToken token = new RefreshToken();
        token.setUserId(user.getId());
        token.setTokenHash(Tokens.sha256Hex(raw));
        token.setFamilyId(familyId);
        token.setExpiresAt(clock.instant().plusSeconds(refreshSeconds));
        token.setUserAgent(userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length())));
        token.setIpAddress(ip);
        refreshTokens.save(token);
        String access = jwt.issue(user.getId(), user.getEmail(), user.roleSet());
        return new AuthTokens(access, raw, jwt.expirationSeconds(), "Bearer", UserView.of(user));
    }
}
