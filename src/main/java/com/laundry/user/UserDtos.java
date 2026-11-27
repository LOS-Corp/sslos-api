package com.laundry.user;

import com.laundry.security.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class UserDtos {

    private UserDtos() {
    }

    /** Ít nhất 8 ký tự, có chữ hoa, chữ thường và số. */
    public static final String PASSWORD_REGEX = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).{8,72}$";
    public static final String PASSWORD_MESSAGE = "Mật khẩu tối thiểu 8 ký tự, gồm chữ hoa, chữ thường và số";

    /** bookingBannedUntil: còn bị khóa đặt trước tới mốc này (đã hết hạn thì null). */
    public record UserView(UUID id, String fullName, String email, String phone, Set<Role> roles,
                           User.Status status, Instant createdAt, Instant lastLoginAt,
                           Instant bookingBannedUntil, String bookingBanReason) {
        public static UserView of(User u) {
            boolean banned = u.bookingBanned(Instant.now());
            return new UserView(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.roleSet(), u.getStatus(),
                    u.getCreatedAt(), u.getLastLoginAt(), banned ? u.getBookingBannedUntil() : null,
                    banned ? u.getBookingBanReason() : null);
        }
    }

    /** until null = gỡ khóa đặt lịch. */
    public record BookingBan(Instant until, @NotBlank @Size(max = 255) String reason) {
    }

    /** Chủ cửa hàng tạo tài khoản nhân viên (chỉ vai trò STAFF). */
    public record CreateStaff(@NotBlank @Size(max = 120) String fullName,
                              @NotBlank @Email @Size(max = 254) String email,
                              @NotBlank String phone,
                              @NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String password) {
    }

    public record UpdateProfile(@NotBlank @Size(max = 120) String fullName, @NotBlank String phone) {
    }

    public record CreateUser(@NotBlank @Size(max = 120) String fullName,
                             @NotBlank @Email @Size(max = 254) String email,
                             @NotBlank String phone,
                             @NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String password,
                             @NotEmpty Set<Role> roles) {
    }

    public record UpdateRoles(@NotEmpty Set<Role> roles, @NotBlank String reason) {
    }

    public record UpdateStatus(User.Status status, @NotBlank String reason) {
    }

    public record AdminUpdateProfile(@NotBlank @Size(max = 120) String fullName, @NotBlank String phone,
                                     @NotBlank String reason) {
    }

    public record ResetPassword(@NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String password,
                                @NotBlank String reason) {
    }
}
