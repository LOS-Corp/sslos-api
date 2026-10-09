package com.laundry.booking.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class BookingConfigDtos {
    private BookingConfigDtos() {}

    public record ResourceCounts(
            int washers,
            int dryers,
            int onlineWashers,
            int onlineDryers,
            int ironingStations,
            int lockers
    ) {}

    public record ValidationIssue(
            String code,
            String message,
            String action,
            String serviceCode
    ) {}

    public record ServiceReadiness(
            String code,
            String name,
            String mode,
            boolean configured,
            boolean enabled,
            BigDecimal price,
            int processingMinutes,
            int secondaryProcessingMinutes,
            int checkInMinutes,
            int transferMinutes,
            int checkoutMinutes,
            boolean ready,
            List<ValidationIssue> issues
    ) {}

    public record BranchReadiness(
            UUID branchId,
            String branchName,
            String branchStatus,
            String timezone,
            boolean bookingSettingsValid,
            boolean bookingHoursValid,
            int configuredServices,
            int enabledServices,
            ResourceCounts resourceCounts,
            boolean ready,
            List<ValidationIssue> issues,
            List<ServiceReadiness> services
    ) {}

    public record BookingSettingsDto(UUID branchId, String timezone) {}

    public record BookingSettingsUpdate(@NotBlank @Size(max = 80) String timezone) {}

    public record ServiceOfferingDto(
            String code,
            String name,
            String mode,
            String description,
            boolean enabled,
            BigDecimal price,
            int processingMinutes,
            int secondaryProcessingMinutes,
            int checkInMinutes,
            int transferMinutes,
            int checkoutMinutes,
            boolean configured
    ) {}

    public record ServiceOfferingUpdate(
            boolean enabled,
            BigDecimal price,
            Integer processingMinutes,
            Integer secondaryProcessingMinutes
    ) {}

    public record ResourceUpdate(
            @NotBlank @Size(max = 50) String resourceCode,
            @NotBlank @Size(max = 120) String displayName,
            boolean enabled
    ) {}
}
