package com.laundry.auth;

import com.laundry.security.Actor;
import com.laundry.user.UserDtos;
import com.laundry.user.UserDtos.UserView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Xác thực")
public class AuthController {

    private final AuthService service;

    public record RegisterRequest(@NotBlank @Size(max = 120) String fullName,
                                  @NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank String phone,
                                  @NotBlank @Pattern(regexp = UserDtos.PASSWORD_REGEX,
                                          message = UserDtos.PASSWORD_MESSAGE) String password) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record ForgotRequest(@NotBlank @Email String email) {
    }

    public record ResetRequest(@NotBlank String token,
                               @NotBlank @Pattern(regexp = UserDtos.PASSWORD_REGEX,
                                       message = UserDtos.PASSWORD_MESSAGE) String newPassword) {
    }

    public record ChangeRequest(@NotBlank String currentPassword,
                                @NotBlank @Pattern(regexp = UserDtos.PASSWORD_REGEX,
                                        message = UserDtos.PASSWORD_MESSAGE) String newPassword) {
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Khách tạo tài khoản (vai trò CUSTOMER, có ví)")
    public UserView register(@Valid @RequestBody RegisterRequest body) {
        return service.register(body.fullName(), body.email(), body.phone(), body.password());
    }

    @PostMapping("/login")
    public AuthService.AuthTokens login(@Valid @RequestBody LoginRequest body, HttpServletRequest http) {
        return service.login(body.email(), body.password(), http.getHeader("User-Agent"), http.getRemoteAddr());
    }

    @PostMapping("/refresh")
    public AuthService.AuthTokens refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest http) {
        return service.refresh(body.refreshToken(), http.getHeader("User-Agent"), http.getRemoteAddr());
    }

    @PostMapping("/logout")
    public Map<String, String> logout() {
        service.logout(Actor.userId());
        return Map.of("message", "Đã đăng xuất");
    }

    @PostMapping("/forgot-password")
    public Map<String, String> forgot(@Valid @RequestBody ForgotRequest body) {
        service.forgotPassword(body.email());
        return Map.of("message", "Nếu email đã đăng ký, bạn sẽ nhận được hướng dẫn đặt lại mật khẩu");
    }

    @PostMapping("/reset-password")
    public Map<String, String> reset(@Valid @RequestBody ResetRequest body) {
        service.resetPassword(body.token(), body.newPassword());
        return Map.of("message", "Đã đặt lại mật khẩu");
    }

    @PostMapping("/change-password")
    public Map<String, String> change(@Valid @RequestBody ChangeRequest body) {
        service.changePassword(Actor.userId(), body.currentPassword(), body.newPassword());
        return Map.of("message", "Đã đổi mật khẩu");
    }
}
