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

        @GetMapping("/api/owner/store/readiness")
        public StoreDtos.Readiness readiness() {
            return service.readiness();
        }
    }
}
