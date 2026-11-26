package com.laundry.store;

import com.laundry.store.StoreDtos.AddClosure;
import com.laundry.store.StoreDtos.ClosureView;
import com.laundry.store.StoreDtos.HourView;
import com.laundry.store.StoreDtos.ReplaceHours;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

public final class StoreControllers {

    private StoreControllers() {
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Công khai · Cửa hàng")
    public static class PublicStoreController {
        private final StoreService service;

        @GetMapping("/api/public/store")
        public StoreDtos.PublicStore store() {
            return service.publicView();
        }
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Chủ cửa hàng · Cấu hình")
    public static class OwnerStoreController {
        private final StoreService service;

        @GetMapping("/api/owner/store/settings")
        public StoreSettings settings() {
            return service.settings();
        }

        @PutMapping("/api/owner/store/settings")
        @Operation(summary = "Đổi tham số (§18); chỉ gửi trường cần đổi, kèm lý do. Đơn đã xác nhận giữ tham số cũ")
        public StoreSettings update(@Valid @RequestBody StoreDtos.UpdateSettings body) {
            return service.update(body);
        }

        @GetMapping("/api/owner/store/hours")
        public List<HourView> hours(@RequestParam(defaultValue = "OPEN") StoreHour.Kind kind) {
            return service.hours(kind);
        }

        @PutMapping("/api/owner/store/hours")
        @Operation(summary = "Thay toàn bộ khung giờ mở cửa (OPEN) hoặc giờ có nhân viên (STAFFED)")
        public List<HourView> replaceHours(@Valid @RequestBody ReplaceHours body) {
            return service.replaceHours(body.kind(), body.slots(), body.reason());
        }

        @GetMapping("/api/owner/store/closures")
        public List<ClosureView> closures() {
            return service.upcomingClosures();
        }

        @PostMapping("/api/owner/store/closures")
        @ResponseStatus(HttpStatus.CREATED)
        public ClosureView addClosure(@Valid @RequestBody AddClosure body) {
            return service.addClosure(body.startsAt(), body.endsAt(), body.reason());
        }

        @DeleteMapping("/api/owner/store/closures/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void removeClosure(@PathVariable UUID id, @RequestParam String reason) {
            service.removeClosure(id, reason);
        }

        @GetMapping("/api/owner/store/services/{service}/impact")
        @Operation(summary = "Trước khi tắt dịch vụ: khách đang dùng (có thì không tắt được) và đơn đặt sắp tới (vẫn phục vụ)")
        public StoreDtos.ServiceImpact impact(@PathVariable String service) {
            return service.isBlank() ? null : this.service.serviceImpact(service.trim().toUpperCase());
        }

        @GetMapping("/api/owner/store/readiness")
        public StoreDtos.Readiness readiness() {
            return service.readiness();
        }
    }

    /**
     * Chính sách hệ thống: quy định đặt lịch, hủy đơn, tiền giữ chỗ, vi phạm, hoàn tiền chung. Quản trị viên chỉnh;
     * Chủ cửa hàng đọc qua GET /api/owner/store/settings (cùng bảng tham số) nhưng không đổi được các trường này.
     */
    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Admin · Chính sách hệ thống")
    public static class AdminPolicyController {
        private final StoreService service;

        public record PolicyView(java.util.Map<String, Object> values, java.time.Instant updatedAt) {
        }

        @GetMapping("/api/admin/policies")
        @Operation(summary = "Các tham số chính sách hiện hành (tên trường như bảng tham số cửa hàng)")
        public PolicyView policies() {
            return view(service.settings());
        }

        @PutMapping("/api/admin/policies")
        @Operation(summary = "Đổi chính sách; chỉ gửi trường cần đổi, kèm lý do. Đơn đã xác nhận giữ chính sách cũ")
        public PolicyView update(@Valid @RequestBody StoreDtos.UpdateSettings body) {
            return view(service.update(body, StoreService.SettingsScope.ADMIN));
        }

        private static PolicyView view(StoreSettings s) {
            java.util.Map<String, Object> values = new java.util.TreeMap<>();
            try {
                for (String name : StoreDtos.POLICY_FIELDS) {
                    var field = StoreSettings.class.getDeclaredField(name);
                    field.setAccessible(true);
                    values.put(name, field.get(s));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("POLICY_FIELDS does not match StoreSettings", e);
            }
            return new PolicyView(values, s.getUpdatedAt());
        }
    }
}
