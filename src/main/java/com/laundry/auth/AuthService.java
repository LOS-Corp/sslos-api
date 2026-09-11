package com.laundry.auth;

import com.laundry.shared.EmailService;
import com.laundry.user.Role;
import com.laundry.user.RoleRepository;
import com.laundry.user.User;
import com.laundry.user.UserRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final long refreshTokenExpirationSeconds;
    private final long resetTokenExpirationSeconds;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordResetTokenRepository passwordResetTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       EmailService emailService,
                       @Value("${app.jwt.refresh-token-expiration-seconds:604800}") long refreshTokenExpirationSeconds,
                       @Value("${app.auth.reset-token-expiration-seconds:900}") long resetTokenExpirationSeconds) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.emailService = emailService;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
        this.resetTokenExpirationSeconds = resetTokenExpirationSeconds;
    }

    @Transactional
    public CustomerResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String phone = request.phone().trim();

        if (!request.password().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("Password confirmation does not match");
        }
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email is already registered");
        }
        if (userRepository.existsByPhone(phone)) {
            throw new DuplicateResourceException("Phone is already registered");
        }

        Role customerRole = roleRepository.findByName("CUSTOMER")
            .orElseThrow(() -> new IllegalStateException("CUSTOMER role is not configured"));
        User user = new User(request.fullName().trim(), email, phone, passwordEncoder.encode(request.password()));
        user.addRole(customerRole);

        try {
            return CustomerResponse.from(userRepository.save(user));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateResourceException("Email or phone is already registered");
        }
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email()))
            .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
            .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
            .orElseThrow(InvalidCredentialsException::new);
        String role = user.getRoles().stream()
            .map(Role::getName)
            .sorted()
            .findFirst()
            .orElse(null);

        String accessToken = jwtService.createToken(user);
        RefreshToken refreshToken = createRefreshToken(user);

        return new LoginResponse(accessToken, refreshToken.getToken(), CustomerResponse.from(user), role);
    }

    @Transactional
    public RefreshTokenResponse refreshToken(RefreshTokenRequest request) {
        RefreshToken currentToken = refreshTokenRepository.findByToken(request.refreshToken())
            .orElseThrow(() -> new InvalidTokenException("Refresh token does not exist"));

        if (!currentToken.isValid()) {
            throw new InvalidTokenException("Refresh token is expired or revoked");
        }

        User user = currentToken.getUser();
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new InvalidTokenException("User account is inactive");
        }

        // Token Rotation: revoke current refresh token
        currentToken.revoke();
        refreshTokenRepository.save(currentToken);

        // Generate new token pair
        String newAccessToken = jwtService.createToken(user);
        RefreshToken newRefreshToken = createRefreshToken(user);

        return new RefreshTokenResponse(newAccessToken, newRefreshToken.getToken());
    }

    @Transactional
    public LogoutResponse logout() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() != null) {
            String principal = authentication.getName();
            try {
                UUID userId = UUID.fromString(principal);
                userRepository.findById(userId).ifPresent(refreshTokenRepository::revokeAllByUser);
            } catch (IllegalArgumentException ignored) {
                userRepository.findByEmail(principal).ifPresent(refreshTokenRepository::revokeAllByUser);
            }
        }
        SecurityContextHolder.clearContext();
        return LogoutResponse.success();
    }

    @Transactional
    public MessageResponse forgotPassword(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        userRepository.findByEmail(email)
            .filter(user -> "ACTIVE".equals(user.getStatus()))
            .ifPresent(user -> {
                passwordResetTokenRepository.invalidateAllByUser(user);
                String tokenValue = UUID.randomUUID().toString().replace("-", "");
                OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(resetTokenExpirationSeconds);
                PasswordResetToken resetToken = new PasswordResetToken(user, tokenValue, expiresAt);
                passwordResetTokenRepository.save(resetToken);
                emailService.sendPasswordResetEmail(user.getEmail(), tokenValue);
            });

        return new MessageResponse("If your email is registered in our system, you will receive password reset instructions.");
    }

    @Transactional
    public MessageResponse resetPassword(ResetPasswordRequest request) {
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("Password confirmation does not match");
        }

        PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(request.token())
            .orElseThrow(() -> new InvalidTokenException("Password reset token is invalid or expired"));

        if (!resetToken.isValid()) {
            throw new InvalidTokenException("Password reset token is invalid or expired");
        }

        User user = resetToken.getUser();
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new InvalidTokenException("User account is inactive");
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        resetToken.markAsUsed();
        passwordResetTokenRepository.save(resetToken);

        refreshTokenRepository.revokeAllByUser(user);

        return new MessageResponse("Password has been reset successfully");
    }

    private RefreshToken createRefreshToken(User user) {
        String tokenValue = UUID.randomUUID().toString().replace("-", "");
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(refreshTokenExpirationSeconds);
        RefreshToken refreshToken = new RefreshToken(user, tokenValue, expiresAt);
        return refreshTokenRepository.save(refreshToken);
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}