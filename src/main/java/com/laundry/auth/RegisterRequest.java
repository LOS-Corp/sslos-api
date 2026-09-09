package com.laundry.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @NotBlank(message = "Full name is required")
    @Size(max = 120, message = "Full name must not exceed 120 characters")
    String fullName,

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Size(max = 254, message = "Email must not exceed 254 characters")
    String email,

    @NotBlank(message = "Phone is required")
    @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "Phone must use E.164 format")
    String phone,

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must contain between 8 and 72 characters")
    @Pattern(regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).+$", message = "Password must contain uppercase, lowercase, digit, and special character")
    String password,

    @NotBlank(message = "Password confirmation is required")
    String confirmPassword
) {
}