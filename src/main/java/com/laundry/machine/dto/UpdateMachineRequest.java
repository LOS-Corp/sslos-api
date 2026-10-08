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
 *       all in-flight MQTT topics and device-side config.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMachineRequest {

    private MachineType type;

    private MachineStatus status;

    private UUID branchId;

    @Size(max = 100, message = "displayName must be at most 100 characters")
    private String displayName;

    @Size(max = 200, message = "locationDescription must be at most 200 characters")
    private String locationDescription;
}
