package com.laundry.auth.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
import com.laundry.auth.service.AuthService;
import com.laundry.user.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * REST Controller for authentication endpoints.
 */
@RestController
@RequestMapping("/api/auth")
@Validated
@Tag(name = "Authentication")
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a customer account")
    public ResponseEntity<AuthenticatedUserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Login and receive a JWT bearer token")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Refresh access token using a valid refresh token")
    public RefreshTokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refreshToken(request);
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout current session", security = @SecurityRequirement(name = "bearerAuth"))
    public LogoutResponse logout() {
        return authService.logout();
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request password reset instructions via email")
    public MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return authService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset password using a reset token")
    public MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }

    @PutMapping("/change-password")
    @Operation(summary = "Change password for authenticated user", security = @SecurityRequirement(name = "bearerAuth"))
    public MessageResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(request.currentPassword(), request.newPassword(), request.confirmPassword());
        return new MessageResponse("Password changed successfully");
    }
}
