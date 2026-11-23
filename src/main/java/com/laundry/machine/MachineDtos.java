package com.laundry.machine;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MachineDtos {

    private MachineDtos() {
    }

    public record MachineView(UUID id, String code, Machine.Type type, BigDecimal capacityKg, int cycleMinutes,
                              Long priceVnd, boolean autoDosing, Machine.AdminStatus adminStatus, String statusReason,
                              MachineStatusResolver.DisplayStatus displayStatus, boolean online, String deviceId,
                              Integer remainingSeconds, Instant busyUntil, int displayOrder,
                              List<PackView> timePacks, UUID priceProfileId, String priceProfileName) {
        static MachineView of(Machine m, MachineStatusResolver.Live live, List<PackView> packs, String profileName) {
            return new MachineView(m.getId(), m.getCode(), m.getMachineType(), m.getCapacityKg(), m.getCycleMinutes(),
                    m.getPriceVnd(), m.isAutoDosing(), m.getAdminStatus(), m.getStatusReason(), live.status(),
                    live.online(), live.deviceId(), live.remainingSeconds(), live.busyUntil(), m.getDisplayOrder(),
                    packs, m.getPriceProfileId(), profileName);
        }
    }

    public record PackView(UUID id, int minutes, long priceVnd, boolean active) {
        static PackView of(DryerTimePack p) {
            return new PackView(p.getId(), p.getMinutes(), p.getPriceVnd(), p.isActive());
        }
    }

    public record CreateMachine(@NotBlank @Pattern(regexp = "^[A-Z0-9-]{1,10}$",
            message = "Số hiệu 1-10 ký tự: chữ in hoa, số, gạch ngang") String code,
                                @NotNull Machine.Type type,
                                @NotNull @DecimalMin("1.0") @DecimalMax("99.9") BigDecimal capacityKg,
                                @Min(1) @Max(240) int cycleMinutes,
                                boolean autoDosing,
                                int displayOrder) {
    }

    public record UpdateMachine(@DecimalMin("1.0") @DecimalMax("99.9") BigDecimal capacityKg,
                                @Min(1) @Max(240) Integer cycleMinutes,
                                Boolean autoDosing,
                                Integer displayOrder,
                                @NotBlank String reason) {
    }

    public record SetPrice(@Min(1000) Long priceVnd, @NotBlank String reason) {
    }

    public record SetStatus(@NotNull Machine.AdminStatus status, @NotBlank String reason) {
    }

    public record PackBody(@Min(1) @Max(120) int minutes, @Min(1000) long priceVnd, boolean active,
                           @NotBlank String reason) {
    }

    public record MaintenanceBody(@NotNull Instant startsAt, @NotNull Instant endsAt, @NotBlank String reason) {
    }

    public record MaintenanceView(UUID id, UUID machineId, Instant startsAt, Instant endsAt, String reason) {
        static MaintenanceView of(MachineReservation r) {
            return new MaintenanceView(r.getId(), r.getMachineId(), r.getStartsAt(), r.getEndsAt(), r.getNote());
        }
    }
}
