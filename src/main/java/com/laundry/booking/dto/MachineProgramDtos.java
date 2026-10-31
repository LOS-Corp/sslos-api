package com.laundry.booking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public final class MachineProgramDtos {
    private MachineProgramDtos() {}

    /**
     * A wash or dry program. Also the snapshot stored on a booking, so a paid
     * booking keeps the parameters it was priced with.
     * Washer: main = wash, finish = spin; dryer: main = dry, finish = cool.
     */
    public record MachineProgramDto(
            String code,
            String machineType,
            String name,
            String description,
            BigDecimal surcharge,
            int mainMinutes,
            int finishMinutes,
            int totalMinutes,
            Integer drumRunSeconds,
            Integer drumPauseSeconds,
            Integer heatPercent,
            Boolean uvEnabled,
            boolean defaultProgram,
            int sortOrder,
            boolean active
    ) {}

    /** {@code code} and {@code machineType} are fixed once created; updates ignore them. */
    public record MachineProgramRequest(
            @Pattern(regexp = "[A-Z0-9_]{2,40}", message = "Mã chỉ gồm chữ in hoa, số và dấu gạch dưới") String code,
            @Pattern(regexp = "WASHER|DRYER") String machineType,
            @NotBlank @Size(max = 80) String name,
            @Size(max = 300) String description,
            @NotNull @DecimalMin("0") BigDecimal surcharge,
            @Min(1) @Max(180) int mainMinutes,
            @Min(0) @Max(30) int finishMinutes,
            @Min(5) @Max(60) Integer drumRunSeconds,
            @Min(3) @Max(30) Integer drumPauseSeconds,
            @Min(0) @Max(100) Integer heatPercent,
            Boolean uvEnabled,
            boolean defaultProgram,
            @Min(0) @Max(999) int sortOrder,
            boolean active
    ) {}

    /** How fast programs run on the machines: 1 = real minutes, N = demo, N times faster. */
    public record ProgramRunMode(@Min(1) @Max(MAX_TIME_DIVISOR) int timeDivisor) {}

    /** Firmware clamps to the same ceiling. */
    public static final int MAX_TIME_DIVISOR = 60;
}
