package com.laundry.device;

import com.laundry.common.Reasons;
import com.laundry.device.link.DeviceIds;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DeviceControllers {

    private DeviceControllers() {
    }

    /** Bo mạch tự gọi (không đăng nhập), xem DeviceRegistrationService. */
    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Bo mạch · Đăng ký")
    public static class RegistrationController {
        private final DeviceRegistrationService service;

        public record ChallengeRequest(@NotBlank @Pattern(regexp = DeviceIds.REGEX) String deviceId) {
        }

        public record RegisterRequest(@NotBlank @Pattern(regexp = DeviceIds.REGEX) String deviceId,
                                      @Size(max = 40) String firmwareVersion,
                                      @Size(max = 16) String deviceType,
                                      @NotBlank @Size(max = 200) String publicKey,
                                      @NotBlank @Size(max = 100) String nonce,
                                      @NotBlank @Size(max = 200) String signature,
                                      Integer rssi) {
        }

        @PostMapping("/api/devices/register/challenge")
        @Operation(summary = "Nonce dùng một lần (1 phút); bo ký LAUNDRYOS-REGISTER|<deviceId>|<nonce>")
        public DeviceRegistrationService.Challenge challenge(@Valid @RequestBody ChallengeRequest body,
                                                            HttpServletRequest http) {
            return service.challenge(body.deviceId(), http.getRemoteAddr());
        }

        @PostMapping("/api/devices/register")
        @Operation(summary = "200: tài khoản MQTT niêm phong bằng khóa của bo; 403 {state: AUTH_FAILED | REVOKED}")
        public ResponseEntity<Object> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest http) {
            Object result = service.register(body.deviceId(), body.publicKey(), body.nonce(), body.signature(),
                    body.firmwareVersion(), body.deviceType(), body.rssi(), http.getRemoteAddr());
            return result instanceof DeviceRegistrationService.Refused
                    ? ResponseEntity.status(HttpStatus.FORBIDDEN).body(result)
                    : ResponseEntity.ok(result);
        }
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Admin · Thiết bị IoT")
    public static class AdminDeviceController {
        private final DeviceAdminService service;
        private final DeviceCommandService commands;

        public record ApproveBody(@NotBlank String fingerprint) {
        }

        public record BindBody(UUID machineId, UUID lockerId, @NotBlank String reason) {
        }

        public record ReasonBody(@NotBlank String reason) {
        }

        @GetMapping("/api/admin/devices")
        public List<DeviceAdminService.DeviceView> list() {
            return service.list();
        }

        @GetMapping("/api/admin/devices/{id}")
        public DeviceAdminService.DeviceView get(@PathVariable String id) {
            return service.get(id);
        }

        @PostMapping("/api/admin/devices/{id}/approve")
        @Operation(summary = "Duyệt khóa của bo; fingerprint là mã XT A1B2-C3D4 hiện trên màn hình bo")
        public DeviceAdminService.DeviceView approve(@PathVariable String id, @Valid @RequestBody ApproveBody body) {
            return service.approve(id, body.fingerprint());
        }

        @PostMapping("/api/admin/devices/{id}/bind")
        public DeviceAdminService.DeviceView bind(@PathVariable String id, @Valid @RequestBody BindBody body) {
            return service.bind(id, body.machineId(), body.lockerId(), body.reason());
        }

        @PostMapping("/api/admin/devices/{id}/unbind")
        public DeviceAdminService.DeviceView unbind(@PathVariable String id, @Valid @RequestBody ReasonBody body) {
            return service.unbind(id, body.reason());
        }

        @PostMapping("/api/admin/devices/{id}/revoke")
        public DeviceAdminService.DeviceView revoke(@PathVariable String id, @Valid @RequestBody ReasonBody body) {
            return service.revoke(id, body.reason());
        }

        @DeleteMapping("/api/admin/devices/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void forget(@PathVariable String id, @RequestParam String reason) {
            service.forget(id, reason);
        }

        @PostMapping("/api/admin/devices/{id}/network-reset")
        @Operation(summary = "Đưa bo về chế độ cài Wi-Fi (bo từ chối khi đang chạy)")
        public Map<String, String> networkReset(@PathVariable String id, @Valid @RequestBody ReasonBody body) {
            Reasons.require(body.reason());
            commands.network(id, "RESET");
            return Map.of("message", "Đã gửi lệnh");
        }
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Nhân viên · Lệnh máy")
    public static class StaffCommandController {
        private final DeviceCommandService commands;

        public record CommandBody(@NotBlank String type, @NotBlank String reason) {
        }

        public record CommandView(UUID id, String type, DeviceCommand.Status status, Instant createdAt) {
        }

        @PostMapping("/api/staff/machines/{id}/commands")
        @Operation(summary = "Ra lệnh cho máy: STOP, RESET, STATUS, SELF_TEST; bắt buộc lý do, ghi nhật ký")
        public CommandView command(@PathVariable UUID id, @Valid @RequestBody CommandBody body) {
            DeviceCommand c = commands.staffMachineCommand(id, body.type().trim().toUpperCase(), body.reason());
            return new CommandView(c.getId(), c.getCommandType(), c.getStatus(), c.getCreatedAt());
        }
    }
}
