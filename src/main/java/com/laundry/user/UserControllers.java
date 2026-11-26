package com.laundry.user;

import com.laundry.common.PageResponse;
import com.laundry.security.Actor;
import com.laundry.security.Role;
import com.laundry.user.UserDtos.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

public final class UserControllers {

    private UserControllers() {
    }

    @RestController
    @RequestMapping("/api/me")
    @RequiredArgsConstructor
    @Tag(name = "Tài khoản của tôi")
    public static class MeController {
        private final UserService service;

        @GetMapping
        public UserView me() {
            return UserView.of(service.get(Actor.userId()));
        }

        @PutMapping
        public UserView update(@Valid @RequestBody UserDtos.UpdateProfile body) {
            return service.updateProfile(Actor.userId(), body);
        }
    }

    @RestController
    @RequestMapping("/api/admin/users")
    @RequiredArgsConstructor
    @Tag(name = "Admin · Tài khoản")
    public static class AdminUserController {
        private final UserService service;

        @GetMapping
        public PageResponse<UserView> search(@RequestParam(required = false) Role role,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
            return PageResponse.of(service.search(role, q, page, size), UserView::of);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public UserView create(@Valid @RequestBody UserDtos.CreateUser body) {
            return service.adminCreate(body);
        }

        @PutMapping("/{id}")
        @Operation(summary = "Sửa họ tên, số điện thoại của tài khoản (bắt buộc lý do, ghi nhật ký)")
        public UserView profile(@PathVariable UUID id, @Valid @RequestBody UserDtos.AdminUpdateProfile body) {
            return service.adminUpdateProfile(id, body.fullName(), body.phone(), body.reason());
        }

        @PutMapping("/{id}/password")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @Operation(summary = "Đặt lại mật khẩu; người dùng bị đăng xuất khỏi mọi phiên")
        public void resetPassword(@PathVariable UUID id, @Valid @RequestBody UserDtos.ResetPassword body) {
            service.adminResetPassword(id, body.password(), body.reason());
        }

        @PutMapping("/{id}/roles")
        public UserView roles(@PathVariable UUID id, @Valid @RequestBody UserDtos.UpdateRoles body) {
            return service.updateRoles(id, body.roles(), body.reason());
        }

        @PutMapping("/{id}/status")
        public UserView status(@PathVariable UUID id, @Valid @RequestBody UserDtos.UpdateStatus body) {
            return service.updateStatus(id, body.status(), body.reason());
        }

        @PutMapping("/{id}/booking-ban")
        @Operation(summary = "Khóa quyền đặt trước của khách tới `until` (vi phạm); until null = gỡ khóa")
        public UserView bookingBan(@PathVariable UUID id, @Valid @RequestBody UserDtos.BookingBan body) {
            return service.setBookingBan(id, body.until(), body.reason());
        }
    }

    /** Chủ cửa hàng: tạo tài khoản nhân viên, khóa / mở, đặt lại mật khẩu. Không cấp được quyền khác STAFF. */
    @RestController
    @RequestMapping("/api/owner/staff")
    @RequiredArgsConstructor
    @Tag(name = "Chủ cửa hàng · Nhân viên")
    public static class OwnerStaffController {
        private final UserService service;

        @GetMapping
        public java.util.List<UserView> list() {
            return service.staff();
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public UserView create(@Valid @RequestBody UserDtos.CreateStaff body) {
            return service.ownerCreateStaff(body);
        }

        @PutMapping("/{id}/status")
        public UserView status(@PathVariable UUID id, @Valid @RequestBody UserDtos.UpdateStatus body) {
            return service.ownerUpdateStaffStatus(id, body.status(), body.reason());
        }

        @PutMapping("/{id}/password")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void resetPassword(@PathVariable UUID id, @Valid @RequestBody UserDtos.ResetPassword body) {
            service.ownerResetStaffPassword(id, body.password(), body.reason());
        }
    }
}
