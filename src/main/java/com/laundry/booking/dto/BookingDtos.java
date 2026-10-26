package com.laundry.booking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class BookingDtos {
    private BookingDtos() {}

    public record ServiceDto(
            String code,
            String name,
            String mode,
            String description,
            int processingMinutes,
            int secondaryProcessingMinutes,
            int checkInMinutes,
            int transferMinutes,
            int checkoutMinutes,
            int pickupDeadlineHours,
            BigDecimal startingPrice,
            List<String> requiredResources
    ) {}

    public record BranchServiceDto(
            UUID branchId,
            String branchName,
            String address,
            String phone,
            String timezone,
            BigDecimal price,
            int processingMinutes,
            int secondaryProcessingMinutes,
            int checkInMinutes,
            int transferMinutes,
            int checkoutMinutes,
            List<BranchHoursDto> openingHours,
            List<String> availableResources
    ) {}

    public record BranchHoursDto(String dayOfWeek, LocalTime opensAt, LocalTime closesAt) {}

    public record RecommendedResourceDto(
            UUID resourceId,
            String resourceType,
            String resourceCode,
            String displayName,
            String location,
            String status
    ) {}

    public record RecommendationDto(
            String recommendationId,
            Instant requestedStartTime,
            Instant occupiedUntil,
            Instant expectedEndTime,
            BigDecimal price,
            String currency,
            int totalMinutes,
            List<RecommendedResourceDto> resources,
            List<SlotPhaseDto> timeline
    ) {}

    /** One machine (plus companion resources for combo services) able to serve the search window. */
    public record MachineSearchResourceDto(
            UUID resourceId,
            String resourceType,
            String resourceCode,
            String displayName,
            String location,
            BigDecimal capacityKg,
            String currentStatus
    ) {}

    /**
     * {@code reservedUntil} includes the turnover buffer; {@code availableStartRanges}
     * lists every other start (minute precision) this exact resource set can take.
     */
    public record MachineSearchOptionDto(
            String optionId,
            List<MachineSearchResourceDto> resources,
            Instant earliestStart,
            Instant expectedCompletion,
            Instant reservedUntil,
            BigDecimal price,
            String currency,
            int totalMinutes,
            List<AvailableStartRange> availableStartRanges,
            List<SlotPhaseDto> timeline
    ) {}

    /** Reason codes: OFFLINE, ERROR, OUT_OF_SERVICE, NOT_COMMISSIONED, DISABLED, FULLY_BOOKED. */
    public record UnavailableMachineDto(
            UUID resourceId,
            String resourceType,
            String resourceCode,
            String displayName,
            BigDecimal capacityKg,
            String reason
    ) {}

    public record MachineSearchResponse(
            UUID branchId,
            String serviceCode,
            String timezone,
            Instant windowStart,
            Instant windowEnd,
            Instant searchedFrom,
            int durationMinutes,
            int bufferMinutes,
            List<MachineSearchOptionDto> options,
            List<UnavailableMachineDto> unavailable
    ) {}

    public record RecommendationsResponse(
            Instant requestedStartTime,
            Instant expectedEndTime,
            Instant preferredEndTime,
            boolean preferredEndTimeSatisfied,
            List<RecommendationDto> recommendations
    ) {}

    public record SlotPhaseDto(
            String phase,
            String resourceType,
            Instant startTime,
            Instant endTime
    ) {}

    public record TimelineAvailabilityResponse(
            LocalDate date,
            String timezone,
            int precisionMinutes,
            Instant dayStart,
            Instant dayEnd,
            int expectedDurationMinutes,
            List<TimelinePhaseTemplate> servicePhases,
            List<ResourceOccupancyTemplate> resourceOccupancies,
            List<TimelineResource> resources,
            List<AvailabilityInterval> operatingIntervals,
            List<AvailabilityInterval> unavailableIntervals,
            List<AvailableStartRange> availableStartRanges
    ) {}

    public record TimelineResource(
           UUID resourceId,
           String resourceType,
           String resourceCode,
           String displayName,
           String status
    ) {}

    public record TimelinePhaseTemplate(
            String phase,
            String resourceType,
            int startOffsetMinutes,
            int endOffsetMinutes
    ) {}

    public record ResourceOccupancyTemplate(
            String resourceType,
            String phase,
            int startOffsetMinutes,
            int endOffsetMinutes
    ) {}

    public record AvailabilityInterval(
            Instant startTime,
            Instant endTime,
            String kind,
            String label,
            String resourceType,
            String resourceCode,
            UUID resourceId
    ) {}

    public record AvailableStartRange(Instant startTime, Instant endExclusive) {}

    public record CreateHoldRequest(
            @NotNull UUID branchId,
            @NotBlank String serviceCode,
            @NotNull Instant startTime,
            @NotNull List<UUID> resourceIds,
            @NotBlank String idempotencyKey,
            /* Program codes; null or blank picks the machine type's default. */
            String washProgram,
            String dryProgram
    ) {}

    public record BookingDto(
            UUID transactionId,
            UUID bookingId,
            String serviceCode,
            String serviceName,
            String serviceMode,
            String status,
            String transactionStatus,
            UUID branchId,
            String branchName,
            String branchAddress,
            String branchPhone,
            Instant startTime,
            Instant expectedEndTime,
            Instant holdExpiresAt,
            BigDecimal totalAmount,
            String currency,
            List<SlotPhaseDto> timeline,
            List<RecommendedResourceDto> resources,
            List<MachineProgramDtos.MachineProgramDto> programs,
            UUID paymentId,
            String paymentStatus,
            String paymentUrl,
            String qrCode
    ) {}

    public record CheckoutDto(UUID transactionId, String idempotencyKey) {}

    public record BookingHoursRequest(
            @NotBlank String timezone,
            @NotNull List<DayHours> days
    ) {}

    public record DayHours(
            @NotNull DayOfWeek dayOfWeek,
            @NotNull LocalTime opensAt,
            @NotNull LocalTime closesAt
    ) {}

    public record BranchOfferingRequest(
            boolean enabled,
            @NotNull @DecimalMin(value = "0.01") BigDecimal price,
            @Positive Integer processingMinutes,
            @Positive Integer secondaryProcessingMinutes
    ) {}

    public record CreateResourceRequest(
            @NotBlank String resourceType,
            @NotBlank String resourceCode,
            @NotBlank String displayName
    ) {}

    public record ResourceDto(
            UUID id,
            UUID branchId,
            String resourceType,
            String resourceCode,
            String displayName,
            boolean enabled
    ) {}
}
