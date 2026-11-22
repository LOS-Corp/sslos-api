package com.laundry.machine.dto;

import com.laundry.machine.entity.MachineType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request/response bodies of the price profile catalog: price per cycle (V64/V65), cycle length (V69) and extra drying
 * time (V66). The pulses that open a third-party machine belong to the machine (V67), not to its price.
 */
public final class PriceProfileDtos {

    private PriceProfileDtos() {}

    /** Default cycle when a caller does not give one: the former default programs (35 min wash, 45 min dry). */
    public static int defaultCycleMinutes(MachineType type) {
        return type == MachineType.DRYER ? 45 : 35;
    }

    /**
     * @param basePrice    price of one cycle (a dryer's basic package), above 0
     * @param cycleMinutes length of one cycle: a washer's booking length, a dryer's exact drying time
     * @param extraMinutes dryers: minutes sold while drying; null = none on sale (then extraPrice is null too)
     * @param extraPrice   price of those minutes
     */
    public record PriceProfileRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull MachineType machineType,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal basePrice,
            @NotNull @Min(value = 10, message = "Thời gian một lượt tối thiểu 10 phút")
            @Max(value = 240, message = "Thời gian một lượt tối đa 240 phút") Integer cycleMinutes,
            @Size(max = 300) String description,
            @Min(1) @Max(120) Integer extraMinutes,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal extraPrice) {

        /** A profile with the default cycle and no extra time. */
        public PriceProfileRequest(String name, MachineType machineType, BigDecimal basePrice, String description) {
            this(name, machineType, basePrice, defaultCycleMinutes(machineType), description, null, null);
        }

        /** A profile with the default cycle. */
        public PriceProfileRequest(String name, MachineType machineType, BigDecimal basePrice, String description,
                                   Integer extraMinutes, BigDecimal extraPrice) {
            this(name, machineType, basePrice, defaultCycleMinutes(machineType), description, extraMinutes, extraPrice);
        }
    }

    /** @param machineCount machines using the profile: it cannot be deleted while above 0 */
    public record PriceProfileView(UUID id, String name, MachineType machineType, BigDecimal basePrice,
                                   int cycleMinutes, String description, Integer extraMinutes, BigDecimal extraPrice,
                                   long machineCount) {}
}
