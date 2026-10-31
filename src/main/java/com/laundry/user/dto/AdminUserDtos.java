package com.laundry.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Operator accounts: Admin manages Admin/Owner accounts, the Owner manages Staff and their branches. */
public final class AdminUserDtos {
    private AdminUserDtos() {}

    static final String PASSWORD_RULE = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^A-Za-z\\d]).+$";
    static final String PASSWORD_MESSAGE = "Mật khẩu cần chữ hoa, chữ thường, số và ký tự đặc biệt";

    public record AdminUserDto(
            UUID id,
            String fullName,
            String email,
            String phone,
            String role,
            String status,
            OffsetDateTime createdAt,
            /** Assigned branches of a Staff account; empty for chain-wide Admin and Owner. */
            List<UUID> branchIds
    ) {}

    public record CreateUserRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "Số điện thoại phải theo dạng +84…") String phone,
            @NotBlank String role,
            @NotBlank @Size(min = 8, max = 72) @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String password,
            List<UUID> branchIds
    ) {}

    public record UpdateUserRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^\\+[1-9][0-9]{7,14}$", message = "Số điện thoại phải theo dạng +84…") String phone,
            @NotBlank String role,
            List<UUID> branchIds
    ) {}

    /** ACTIVE or LOCKED. */
    public record UpdateStatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|LOCKED") String status) {}

    public record ResetPasswordRequest(
            @NotBlank @Size(min = 8, max = 72) @Pattern(regexp = PASSWORD_RULE, message = PASSWORD_MESSAGE) String password
    ) {}
}
