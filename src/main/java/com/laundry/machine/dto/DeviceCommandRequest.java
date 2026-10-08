package com.laundry.machine.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for issuing a command to a machine.
 *
 * <p>Supported commands: START, STOP, RESET, STATUS, TARE, PAUSE, RESUME, CANCEL.
 *
 * <p>STOP semantics: STOP is a normal cycle cancellation. The firmware
 * responds with CYCLE_STOPPED → state AVAILABLE. STOP does NOT mark the
 * machine as ERROR. There is no physical STOP button on the ESP32 firmware
 * v2 board.
 *
 * <p>The bookingId is required for START (to validate authorization).
 * It is optional/ignored for RESET, TARE, STATUS.
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
}