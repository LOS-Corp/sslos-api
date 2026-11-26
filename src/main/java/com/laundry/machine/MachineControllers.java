package com.laundry.machine;

import com.laundry.machine.MachineDtos.MachineView;
import com.laundry.machine.MachineDtos.MaintenanceView;
import com.laundry.machine.MachineDtos.PackView;
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

public final class MachineControllers {

    private MachineControllers() {
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Công khai · Máy")
    public static class PublicMachineController {
        private final MachineService service;

        @GetMapping("/api/public/machines")
        @Operation(summary = "Máy đang phục vụ: số hiệu, sức chứa, giá, trạng thái hiện tại")
        public List<MachineView> list() {
            return service.list(false);
        }
    }

    /** Danh mục máy: quản trị hệ thống và chủ cửa hàng (§2). */
    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Quản lý máy")
    public static class ManageMachineController {
        private final MachineService service;
        private final MachineInsightService insights;

        @GetMapping({"/api/admin/machines", "/api/owner/machines", "/api/staff/machines"})
        public List<MachineView> list(@RequestParam(defaultValue = "false") boolean includeRetired) {
            return service.list(includeRetired);
        }

        @GetMapping({"/api/admin/machines/{id}", "/api/owner/machines/{id}", "/api/staff/machines/{id}"})
        public MachineView get(@PathVariable UUID id) {
            return service.view(id);
        }

        /** Quản trị viên và Chủ cửa hàng thêm máy giặt, máy sấy. */
        @PostMapping({"/api/admin/machines", "/api/owner/machines"})
        @ResponseStatus(HttpStatus.CREATED)
        public MachineView create(@Valid @RequestBody MachineDtos.CreateMachine body) {
            return service.create(body);
        }

        @PutMapping({"/api/admin/machines/{id}", "/api/owner/machines/{id}"})
        public MachineView update(@PathVariable UUID id, @Valid @RequestBody MachineDtos.UpdateMachine body) {
            return service.update(id, body);
        }

        @GetMapping({"/api/admin/machines/{id}/insight", "/api/owner/machines/{id}/insight",
                "/api/staff/machines/{id}/insight"})
        @Operation(summary = "Máy + bo + số lượt/doanh thu hôm nay và N ngày (1-90) + lượt sắp tới + lịch bảo trì")
        public MachineInsightService.Insight insight(@PathVariable UUID id, @RequestParam(defaultValue = "14") int days) {
            return insights.insight(id, days);
        }

        @GetMapping({"/api/admin/machines/{id}/activity", "/api/owner/machines/{id}/activity",
                "/api/staff/machines/{id}/activity"})
        @Operation(summary = "Lịch sử máy, mới nhất trước: lượt chạy, lệnh, thao tác quản lý, gắn/gỡ bo")
        public List<MachineInsightService.ActivityItem> activity(@PathVariable UUID id,
                                                                 @RequestParam(defaultValue = "50") int limit) {
            return insights.activity(id, limit);
        }
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Chủ cửa hàng · Giá, gói sấy, bảo trì")
    public static class OwnerMachineController {
        private final MachineService service;

        @PutMapping("/api/owner/machines/{id}/price")
        @Operation(summary = "Đặt giá một lượt; null = ngừng bán. Đơn đã đặt giữ giá cũ")
        public MachineView price(@PathVariable UUID id, @Valid @RequestBody MachineDtos.SetPrice body) {
            return service.setPrice(id, body.priceVnd(), body.reason());
        }

        @PostMapping("/api/owner/machines/{id}/time-packs")
        @ResponseStatus(HttpStatus.CREATED)
        public PackView addPack(@PathVariable UUID id, @Valid @RequestBody MachineDtos.PackBody body) {
            return service.addPack(id, body);
        }

        @PutMapping("/api/owner/machines/{id}/time-packs/{packId}")
        public PackView updatePack(@PathVariable UUID id, @PathVariable UUID packId,
                                   @Valid @RequestBody MachineDtos.PackBody body) {
            return service.updatePack(id, packId, body);
        }

        @GetMapping("/api/owner/machines/{id}/maintenance")
        public List<MaintenanceView> maintenance(@PathVariable UUID id) {
            return service.maintenance(id);
        }

        @PostMapping("/api/owner/machines/{id}/maintenance")
        @ResponseStatus(HttpStatus.CREATED)
        @Operation(summary = "Lên lịch bảo trì; trùng lượt đã đặt thì từ chối và trả danh sách đơn bị trùng")
        public MaintenanceView addMaintenance(@PathVariable UUID id, @Valid @RequestBody MachineDtos.MaintenanceBody body) {
            return service.addMaintenance(id, body.startsAt(), body.endsAt(), body.reason());
        }

        @DeleteMapping("/api/owner/machines/{id}/maintenance/{reservationId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void cancelMaintenance(@PathVariable UUID id, @PathVariable UUID reservationId,
                                      @RequestParam String reason) {
            service.cancelMaintenance(id, reservationId, reason);
        }
    }

    @RestController
    @RequiredArgsConstructor
    @Tag(name = "Nhân viên · Máy")
    public static class StaffMachineController {
        private final MachineService service;

        @PutMapping("/api/staff/machines/{id}/status")
        @Operation(summary = "Chuyển máy sang Lỗi/Bảo trì (hoặc ACTIVE). Đơn đã gán được chuyển máy hoặc hoàn 100% (§12)")
        public MachineView status(@PathVariable UUID id, @Valid @RequestBody MachineDtos.SetStatus body) {
            return service.setStatus(id, body.status(), body.reason());
        }
    }
}
