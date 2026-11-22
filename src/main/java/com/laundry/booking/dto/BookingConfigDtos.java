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
            /* Washer services (V61): price per kilogram; price is then the fixed part. */
            BigDecimal pricePerKg,
            int processingMinutes,
            int secondaryProcessingMinutes,
            int checkInMinutes,
            int transferMinutes,
            int checkoutMinutes,
            boolean ready,
            List<ValidationIssue> issues
    ) {}

    public record StoreReadiness(
            String storeName,
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

    /**
     * {@code turnoverBufferMinutes}: idle time kept after every booking before the next one may start. V70: the
     * self-service check-in, check-out and Wash–Dry transfer times of the store.
     */
    public record BookingSettingsDto(String timezone, int turnoverBufferMinutes, int checkInMinutes,
                                     int checkoutMinutes, int transferMinutes) {}

    /** Null fields keep their value. */
    public record BookingSettingsUpdate(
            @NotBlank @Size(max = 80) String timezone,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(120)
            Integer turnoverBufferMinutes,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(60)
            Integer checkInMinutes,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(120)
            Integer checkoutMinutes,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(120)
            Integer transferMinutes) {

        /** Timezone and buffer only. */
        public BookingSettingsUpdate(String timezone, Integer turnoverBufferMinutes) {
            this(timezone, turnoverBufferMinutes, null, null, null);
        }
    }

    public record ServiceOfferingDto(
            String code,
            String name,
            String mode,
            String description,
            boolean enabled,
            BigDecimal price,
            /* Assisted laundry (V65): price per kilogram charged after staff weighed it; price is what is paid when
               booking. Null for self-service, priced per cycle. */
            BigDecimal pricePerKg,
            int processingMinutes,
            int secondaryProcessingMinutes,
            int checkInMinutes,
            int transferMinutes,
            int checkoutMinutes,
            boolean configured,
            /* True for assisted laundry, priced by weight (V65). */
            boolean priceByWeight,
            /* V68: self-service on washers/dryers, priced by each machine's price profile; price is then the cheapest
               booking ("from"), null while no commissioned machine has a profile. */
            boolean priceByMachine
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
