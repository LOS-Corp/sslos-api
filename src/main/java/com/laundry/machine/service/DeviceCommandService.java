package com.laundry.machine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.machine.dto.DeviceCommandDto;
import com.laundry.machine.dto.DeviceCommandRequest;
import com.laundry.machine.entity.DeviceCommand;
import com.laundry.machine.entity.DeviceCommandStatus;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStartAuthorization;
import com.laundry.machine.entity.MachineStartAuthorizationStatus;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.repository.DeviceCommandRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import com.laundry.mqtt.service.MqttPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Service for issuing and tracking commands sent to devices.
 *
 * <p>Command flow:
 * <ol>
 *   <li>Authenticate user (done by Spring Security before reaching here).</li>
 *   <li>Validate machine exists and is online.</li>
 *   <li>Validate machine state allows this command.</li>
 *   <li>For START: validate booking ownership and payment.</li>
 *   <li>Create {@link DeviceCommand} record (PENDING).</li>
 *   <li>Publish MQTT message to {@code laundry/washer/{branchId}/{machineId}/command}.</li>
 *   <li>Update record to SENT.</li>
 * </ol>
 *
 * <p>STOP semantics: STOP cancels the current cycle. It does NOT mark the
 * machine as ERROR. The firmware responds with {@code CYCLE_STOPPED} → state
 * AVAILABLE. The new ESP32 firmware has no physical STOP button.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceCommandService {

    private final MachineRepository machineRepository;
    private final DeviceCommandRepository deviceCommandRepository;
    private final MachineStartAuthorizationRepository startAuthorizationRepository;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    @Value("${app.machine.command-timeout-seconds:15}")
    private int commandTimeoutSeconds;

    private static final Set<String> COMMANDS_REQUIRING_BOOKING = Set.of("START");

    /**
     * Commands that bypass status validation. Used for RESET (recovery) and
     * STATUS (liveness probe).
     */
    private static final Set<String> UNRESTRICTED_COMMANDS = Set.of("RESET", "STATUS");

    /**
     * Issue a command to a machine.
     *
     * @param machineCode the machine's code (e.g., "WM001")
     * @param request     the command request DTO
     * @param userId      the authenticated user issuing the command
     * @return the created DeviceCommand DTO
     */
    @Transactional
    public DeviceCommandDto issueCommand(String machineCode, DeviceCommandRequest request, UUID userId) {
        String cmd = request.getCommand().toUpperCase().trim();

        // 1. Find machine
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> new IllegalArgumentException("Machine not found: " + machineCode));

        // 2. Validate machine is online
        if (!machine.isOnline()) {
            throw new IllegalStateException("Machine " + machineCode + " is currently offline");
        }

        // 3. Validate machine status allows this command
        validateCommandForStatus(cmd, machine);

        // 4. For START: validate booking
        UUID bookingId = request.getBookingId();
        if (COMMANDS_REQUIRING_BOOKING.contains(cmd)) {
            if (bookingId == null) {
                throw new IllegalArgumentException("bookingId is required for START command");
            }
            validateBookingForStart(machine, bookingId, userId);
        }

        // 5. Create DeviceCommand record
        String commandId = "CMD-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        DeviceCommand command = DeviceCommand.builder()
                .commandId(commandId)
                .machineId(machine.getId())
                .machineCode(machineCode)
                .command(cmd)
                .bookingId(bookingId)
                .issuedByUserId(userId)
                .status(DeviceCommandStatus.PENDING)
                .timeoutAt(now.plusSeconds(commandTimeoutSeconds))
                .build();

        command = deviceCommandRepository.save(command);
        log.info("[{}] Command created: commandId={}, cmd={}, user={}", machineCode, commandId, cmd, userId);

        // 6. Build and publish MQTT payload
        String mqttPayload = buildCommandPayload(cmd, commandId, bookingId, now);
        UUID branchId = machine.getBranchId();
        String topic = mqttPublisher.buildWasherCommandTopic(branchId, machineCode);

        try {
            mqttPublisher.publish(topic, mqttPayload);

            // 7. Update status to SENT
            command.setStatus(DeviceCommandStatus.SENT);
            command.setSentAt(OffsetDateTime.now());
            command = deviceCommandRepository.save(command);

            log.info("[{}] Command published: commandId={}, topic={}", machineCode, commandId, topic);

        } catch (Exception e) {
            command.setStatus(DeviceCommandStatus.FAILED);
            command.setAckReason("Publish failed: " + e.getMessage());
            deviceCommandRepository.save(command);
            log.error("[{}] Failed to publish command: {}", machineCode, e.getMessage(), e);
            throw new IllegalStateException("Failed to send command to device: " + e.getMessage(), e);
        }

        return toDto(command);
    }

    /**
     * Builds the MQTT command payload.
     *
     * <p>The "command" field is always present for backward compatibility
     * with older firmware. Firmware v2 additionally parses {@code commandId}.
     */
    private String buildCommandPayload(String command, String commandId,
                                        UUID bookingId, OffsetDateTime issuedAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("command", command);
        payload.put("commandId", commandId);
        if (bookingId != null) {
            payload.put("bookingId", bookingId.toString());
        }
        payload.put("issuedAt", issuedAt.toString());

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize command payload — using fallback", e);
            return "{\"command\":\"" + command + "\"}";
        }
    }

    /**
     * Validate that the current machine status allows the requested command.
     *
     * <p>STOP is a normal cycle cancellation. The firmware transitions the
     * cycle to CYCLE_STOPPED → AVAILABLE — never ERROR.
     */
    private void validateCommandForStatus(String cmd, Machine machine) {
        MachineStatus status = machine.getStatus();

        if (UNRESTRICTED_COMMANDS.contains(cmd)) {
            return;
        }

        switch (cmd) {
            case "START" -> {
                if (status != MachineStatus.WAITING_START && status != MachineStatus.AVAILABLE) {
                    throw new IllegalStateException(
                        "Cannot START machine in status: " + status +
                        ". Machine must be AVAILABLE or WAITING_START.");
                }
            }
            case "STOP", "CANCEL" -> {
                // STOP/CANCEL are valid in any non-OFFLINE state.
                if (status == MachineStatus.OFFLINE || status == MachineStatus.COMPLETED) {
                    throw new IllegalStateException("Cannot " + cmd + " machine in status: " + status);
                }
            }
            case "PAUSE" -> {
                if (status != MachineStatus.RUNNING) {
                    throw new IllegalStateException("Cannot PAUSE machine not in RUNNING status");
                }
            }
            case "RESUME" -> {
                if (status != MachineStatus.PAUSED) {
                    throw new IllegalStateException("Cannot RESUME machine not in PAUSED status");
                }
            }
            case "TARE" -> {
                // TARE is allowed whenever the device is online (handled by caller).
            }
            default -> log.warn("Unrecognized command '{}' — allowing with no status restriction", cmd);
        }
    }

    private void validateBookingForStart(Machine machine, UUID bookingId, UUID userId) {
        // Phase 9 wiring: the customer's START request now goes through the
        // QR check-in flow (POST /api/machines/check-in) and the physical
        // START button is unlocked via MachineStartAuthorization. The legacy
        // remote-START REST endpoint is kept for staff / admin override only —
        // we still require an active authorization tied to a reservation.
        if (userId == null) {
            throw new IllegalArgumentException(
                    "Authentication is required to START a machine (no user context)");
        }
        var active = startAuthorizationRepository.findActiveByMachine(machine.getId());
        if (active.isEmpty()) {
            throw new IllegalStateException(
                    "No active START authorization for machine " + machine.getMachineCode()
                            + ". Customer must scan the QR at the machine first.");
        }
        MachineStartAuthorization auth = active.get();
        if (auth.getReservationId() == null || !auth.getReservationId().equals(bookingId)) {
            throw new IllegalStateException(
                    "bookingId does not match the active authorization for this machine");
        }
        if (auth.getStatus() != MachineStartAuthorizationStatus.GRANTED
                && auth.getStatus() != MachineStartAuthorizationStatus.START_PENDING) {
            throw new IllegalStateException(
                    "Active authorization is in status " + auth.getStatus()
                            + "; press START on the device after the device ACKs.");
        }
        log.info("[{}] START authorization OK for reservation {} (auth {})",
                machine.getMachineCode(), bookingId, auth.getId());
    }

    private DeviceCommandDto toDto(DeviceCommand cmd) {
        return DeviceCommandDto.builder()
                .id(cmd.getId())
                .commandId(cmd.getCommandId())
                .machineId(cmd.getMachineId())
                .machineCode(cmd.getMachineCode())
                .command(cmd.getCommand())
                .bookingId(cmd.getBookingId())
                .status(cmd.getStatus())
                .sentAt(cmd.getSentAt())
                .acknowledgedAt(cmd.getAcknowledgedAt())
                .ackResult(cmd.getAckResult())
                .ackReason(cmd.getAckReason())
                .timeoutAt(cmd.getTimeoutAt())
                .createdAt(cmd.getCreatedAt())
                .build();
    }
}