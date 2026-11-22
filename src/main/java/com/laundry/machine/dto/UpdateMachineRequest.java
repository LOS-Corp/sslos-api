package com.laundry.machine.dto;

import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for partially updating a machine.
 *
 * <p>All fields are optional — only non-null fields are applied. This keeps the
 * endpoint idempotent for the common "just rename" / "just relocate" use cases
 * without forcing the admin to resend every field.
 *
 * <p>Business rules enforced in {@code MachineService.updateMachine}:
 * <ul>
 *   <li>Status transitions are validated through {@code MachineStateService} —
 *       arbitrary admin-forced transitions are NOT allowed.</li>
 *   <li>machineCode is immutable after creation. Changing it would invalidate
 *       all in-flight device traffic and device-side config.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMachineRequest {

    private MachineType type;

    private MachineStatus status;


    @Size(max = 100, message = "displayName must be at most 100 characters")
    private String displayName;

    @Size(max = 200, message = "locationDescription must be at most 200 characters")
    private String locationDescription;

    @jakarta.validation.constraints.DecimalMin(value = "0.1", message = "capacityKg must be greater than 0")
    @jakarta.validation.constraints.DecimalMax(value = "9999.9", message = "capacityKg is too large")
    private java.math.BigDecimal capacityKg;

    /** Price profile to apply (V64); must be of the machine's type. Null leaves it unchanged. */
    private UUID priceProfileId;

    /** True removes the price profile: the machine is priced by the service offering again. */
    private Boolean clearPriceProfile;

    /** Retrofit board (V67): pulses that open the third-party machine for one cycle. Null leaves it unchanged. */
    @jakarta.validation.constraints.Min(value = 1, message = "vendPulses must be 1-50")
    @jakarta.validation.constraints.Max(value = 50, message = "vendPulses must be 1-50")
    private Integer vendPulses;

    /** True goes back to the firmware default (1 pulse). */
    private Boolean clearVendPulses;

    /** Retrofit dryer (V67): pulses for each purchase of extra drying time. Null leaves it unchanged. */
    @jakarta.validation.constraints.Min(value = 1, message = "extraVendPulses must be 1-50")
    @jakarta.validation.constraints.Max(value = 50, message = "extraVendPulses must be 1-50")
    private Integer extraVendPulses;

    /** True goes back to 1 pulse per purchase. */
    private Boolean clearExtraVendPulses;
}
