package com.laundry.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Password reset request DTO with email and 6-digit OTP code.
 */
public record ResetPasswordRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    String email,

    @NotBlank(message = "OTP code is required")
    @Size(min = 6, max = 6, message = "OTP code must be 6 digits")
    @Pattern(regexp = "^\\d{6}$", message = "OTP code must be 6 digits")
    String otpCode,

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must contain between 8 and 72 characters")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).+$",
        message = "Password must contain uppercase, lowercase, digit, and special character"
    )
    String newPassword,

    @NotBlank(message = "Password confirmation is required")
    String confirmPassword
) {}
