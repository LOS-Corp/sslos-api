package com.laundry.auth.service;

import com.laundry.auth.dto.request.ChangePasswordRequest;
import com.laundry.auth.dto.request.ForgotPasswordRequest;
import com.laundry.auth.dto.request.LoginRequest;
import com.laundry.auth.dto.request.RefreshTokenRequest;
import com.laundry.auth.dto.request.RegisterRequest;
import com.laundry.auth.dto.request.ResetPasswordRequest;
import com.laundry.auth.dto.response.AuthenticatedUserResponse;
import com.laundry.auth.dto.response.LoginResponse;
import com.laundry.auth.dto.response.LogoutResponse;
import com.laundry.auth.dto.response.MessageResponse;
import com.laundry.auth.dto.response.RefreshTokenResponse;
import com.laundry.auth.entity.OtpCode;
import com.laundry.auth.entity.RefreshToken;
import com.laundry.auth.exception.InvalidCredentialsException;
import com.laundry.auth.exception.InvalidTokenException;
import com.laundry.auth.repository.OtpCodeRepository;
import com.laundry.auth.repository.RefreshTokenRepository;
import com.laundry.auth.security.JwtService;
import com.laundry.shared.exception.DuplicateResourceException;
import com.laundry.shared.EmailService;
import com.laundry.user.entity.Role;
import com.laundry.user.entity.User;
import com.laundry.user.repository.RoleRepository;
import com.laundry.user.repository.UserRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Service handling all authentication operations.
 */
@Service
public class AuthService {

    private static final int OTP_LENGTH = 6;
    private static final int OTP_EXPIRATION_MINUTES = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final OtpCodeRepository otpCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final long refreshTokenExpirationSeconds;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       OtpCodeRepository otpCodeRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       EmailService emailService,
                       @Value("${app.jwt.refresh-token-expiration-seconds:604800}") long refreshTokenExpirationSeconds) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.otpCodeRepository = otpCodeRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.emailService = emailService;
        this.refreshTokenExpirationSeconds = refreshTokenExpirationSeconds;
    }

    @Transactional
    public AuthenticatedUserResponse register(RegisterRequest request) {
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
            return AuthenticatedUserResponse.from(userRepository.save(user));
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

        return new LoginResponse(accessToken, refreshToken.getToken(), AuthenticatedUserResponse.from(user), role);
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
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() != null) {
            String principal = authentication.getName();
            try {
                UUID userId = UUID.fromString(principal);
                userRepository.findById(userId).ifPresent(refreshTokenRepository::revokeAllByUser);
            } catch (IllegalArgumentException ignored) {
                userRepository.findByEmail(principal).ifPresent(refreshTokenRepository::revokeAllByUser);
            }
        }
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        return LogoutResponse.success();
    }

    @Transactional
    public MessageResponse forgotPassword(ForgotPasswordRequest request) {
        String email = normalizeEmail(request.email());
        userRepository.findByEmail(email)
            .filter(user -> "ACTIVE".equals(user.getStatus()))
            .ifPresent(user -> {
                // Invalidate all existing OTP codes for this user
                otpCodeRepository.invalidateAllByUser(user);

                // Generate 6-digit OTP
                String otpCode = generateOtp();

                // Save OTP with 5 minutes expiration
                OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(OTP_EXPIRATION_MINUTES);
                OtpCode otp = new OtpCode(user, otpCode, expiresAt);
                otpCodeRepository.save(otp);

                // Send OTP via email
                emailService.sendPasswordResetEmail(user.getEmail(), user.getFullName(), otpCode);
            });

        return new MessageResponse("If your email is registered in our system, you will receive password reset instructions.");
    }

    @Transactional
    public MessageResponse resetPassword(ResetPasswordRequest request) {
        String email = normalizeEmail(request.email());

        // Validate password confirmation
        if (!request.newPassword().equals(request.confirmPassword())) {
            throw new IllegalArgumentException("Password confirmation does not match");
        }

        // Find user by email
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new InvalidTokenException("Invalid OTP or email"));

        // Find valid OTP
        OtpCode otp = otpCodeRepository.findValidOtpByUserAndCode(user, request.otpCode())
            .orElseThrow(() -> new InvalidTokenException("Invalid OTP or OTP has expired"));

        // Check user status
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new InvalidTokenException("User account is inactive");
        }

        // Update password
        user.changePassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Mark OTP as used
        otp.markAsUsed();
        otpCodeRepository.save(otp);

        // Revoke all refresh tokens for security
        refreshTokenRepository.revokeAllByUser(user);

        return new MessageResponse("Password has been reset successfully");
    }

    /**
     * Generate a cryptographically secure 6-digit OTP.
     */
    private String generateOtp() {
        int otp = SECURE_RANDOM.nextInt(900000) + 100000; // 100000-999999
        return String.valueOf(otp);
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
