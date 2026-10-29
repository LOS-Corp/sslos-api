package com.laundry.machine.dto;

import com.laundry.machine.entity.MachineType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for creating a new machine.
 *
 * <p>Admin-only. Used by {@code POST /api/admin/machines}.
 *
 * <p>Validation rules:
 * <ul>
 *   <li>{@code machineCode}: required, 2-50 chars, uppercase letters / digits / '-' / '_'.
 *       The pattern intentionally rejects lowercase so the MQTT topic segment
 *       stays canonical (e.g. "WM001", "DRYER-02").</li>
 *   <li>{@code type}: required, must match {@link MachineType}.</li>
 *   <li>{@code branchId}: required UUID — MQTT topics are scoped per branch.</li>
 *   <li>{@code displayName}: optional, max 100 chars.</li>
 *   <li>{@code locationDescription}: optional, max 200 chars.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateMachineRequest {

    @NotBlank(message = "machineCode is required")
    @Size(min = 2, max = 50, message = "machineCode must be 2-50 characters")
    @Pattern(
        regexp = "^[A-Z0-9][A-Z0-9_-]*$",
        message = "machineCode must contain only uppercase letters, digits, '-' or '_' (e.g. WM001, DRYER-02)"
    )
    private String machineCode;

    @NotNull(message = "type is required")
    private MachineType type;

    @NotNull(message = "branchId is required — MQTT topics are scoped per branch")
    private UUID branchId;

    @Size(max = 100, message = "displayName must be at most 100 characters")
    private String displayName;

    @Size(max = 200, message = "locationDescription must be at most 200 characters")
    private String locationDescription;

    @jakarta.validation.constraints.DecimalMin(value = "0.1", message = "capacityKg must be greater than 0")
    @jakarta.validation.constraints.DecimalMax(value = "9999.9", message = "capacityKg is too large")
    private java.math.BigDecimal capacityKg;
}