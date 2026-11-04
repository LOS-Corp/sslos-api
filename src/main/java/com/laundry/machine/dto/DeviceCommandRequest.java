package com.laundry.machine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for issuing a command to a machine.
 *
 * <p>Supported commands: STOP, RESET, STATUS. Machines are started by QR check-in and the START button.
 *
 * <p>STOP semantics: STOP is a normal cycle cancellation. The firmware
 * responds with CYCLE_STOPPED → state AVAILABLE. STOP does NOT mark the
 * machine as ERROR. There is no physical STOP button on the ESP32 firmware
 * v2 board.
 *
 * <p>{@code reason} is mandatory for every Staff/Owner/Admin override except STATUS (BR-25).
 */
@Data
@NoArgsConstructor
public class DeviceCommandRequest {

    @NotBlank(message = "command is required")
    private String command;

    /**
     * Required for START command. Identifies the booking/transaction
     * that authorizes this machine start.
     */
    private UUID bookingId;

    /** Why an operator overrides the machine (BR-25); kept in the audit log. */
    @Size(max = 500, message = "reason must be at most 500 characters")
    private String reason;
}
