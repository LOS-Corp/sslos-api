package com.laundry.device;

import com.laundry.device.MachineDeploymentService.ProvisioningView;
import com.laundry.device.MachineDeploymentService.SelfTestReceipt;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mục "Lắp đặt" của chi tiết máy, theo mã máy (W01). Quản trị viên và chủ cửa hàng (xem SecurityConfig):
 * web quản lý dùng tiền tố /api/admin cho cả hai vai trò.
 */
@RestController
@RequestMapping("/api/admin/machines/{code}")
@RequiredArgsConstructor
@Tag(name = "Quản trị · Lắp đặt máy")
public class MachineDeploymentController {

    private final MachineDeploymentService deployment;

    public record PairBody(@NotBlank String deviceId) {
    }

    public record MaintenanceBody(String reason) {
    }

    @GetMapping("/provisioning")
    @Operation(summary = "Trạng thái lắp đặt, kết nối bo và kết quả self-test gần nhất")
    public ProvisioningView provisioning(@PathVariable String code) {
        return deployment.provisioning(code);
    }

    @PostMapping("/pairing")
    @Operation(summary = "Gắn bo đã duyệt vào máy chưa có bo; self-test chạy khi người vận hành bấm (POST /self-test)")
    public ProvisioningView pair(@PathVariable String code, @Valid @RequestBody PairBody body) {
        return deployment.pair(code, body.deviceId());
    }

    @PostMapping("/self-test")
    @Operation(summary = "Gửi lệnh SELF_TEST; kết quả về sau qua event SELF_TEST_RESULT của bo")
    public SelfTestReceipt selfTest(@PathVariable String code) {
        return deployment.selfTest(code);
    }

    @PostMapping("/commission")
    @Operation(summary = "Đưa máy vào hoạt động (hoặc hoàn tất bảo trì) sau khi self-test đạt")
    public ProvisioningView commission(@PathVariable String code) {
        return deployment.commission(code);
    }

    @PostMapping("/maintenance")
    @Operation(summary = "Tạm ngừng cho thuê máy đang hoạt động để sửa; từ chối khi máy còn lượt mở")
    public ProvisioningView maintenance(@PathVariable String code, @RequestBody(required = false) MaintenanceBody body) {
        return deployment.startMaintenance(code, body == null ? null : body.reason());
    }

    @PostMapping("/unpair")
    @Operation(summary = "Hủy liên kết: thu hồi bo của máy, máy về chưa gán thiết bị")
    public ResponseEntity<Void> unpair(@PathVariable String code) {
        deployment.unpair(code);
        return ResponseEntity.noContent().build();
    }
}
