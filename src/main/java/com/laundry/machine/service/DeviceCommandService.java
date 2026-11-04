package com.laundry.machine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.audit.service.AuditAction;
import com.laundry.audit.service.AuditService;
import com.laundry.audit.service.AuditService.AuditEntry;
import com.laundry.machine.dto.DeviceCommandDto;
import com.laundry.machine.dto.DeviceCommandRequest;
import com.laundry.machine.entity.DeviceCommand;
import com.laundry.machine.entity.DeviceCommandStatus;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.repository.DeviceCommandRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.shared.error.BusinessException;
import com.laundry.shared.error.ErrorCode;
import com.laundry.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Issues commands to devices (UC-18).
 *
 * <p>Command flow:
 * <ol>
 *   <li>{@link RemoteCommandAuthorizer} decides whether the caller may send this command (FR-ACT-06, BR-25).</li>
 *   <li>Validate the machine is online and its state allows the command.</li>
 *   <li>Persist {@link DeviceCommand} as PENDING and write the audit entry — one transaction.</li>
 *   <li>After commit, {@link DeviceCommandDispatcher} publishes to MQTT and marks it SENT or FAILED,
 *       so a rolled-back request never reaches a device (ADR-08).</li>
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
    private final RemoteCommandAuthorizer commandAuthorizer;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;

    @Value("${app.machine.command-timeout-seconds:15}")
    private int commandTimeoutSeconds;

    /**
     * Commands that bypass status validation. Used for RESET (recovery) and
     * STATUS (liveness probe).
     */
    private static final Set<String> UNRESTRICTED_COMMANDS = Set.of("RESET", "STATUS");

    @Transactional
    public DeviceCommandDto issueCommand(String machineCode, DeviceCommandRequest request, CurrentUser actor) {
        String cmd = request.getCommand().toUpperCase(Locale.ROOT).trim();
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> MachineNotFoundException.byCode(machineCode));

        commandAuthorizer.check(actor, machine, cmd, request.getReason());
        if (!machine.isOnline()) {
            throw BusinessException.of(ErrorCode.MACHINE_OFFLINE, "Machine " + machineCode + " is currently offline");
        }
        validateCommandForStatus(cmd, machine);

        UUID bookingId = request.getBookingId();
        OffsetDateTime now = OffsetDateTime.now();
        String commandId = "CMD-" + UUID.randomUUID();
        DeviceCommand command = deviceCommandRepository.save(DeviceCommand.builder()
                .commandId(commandId)
                .machineId(machine.getId())
                .machineCode(machineCode)
                .command(cmd)
                .bookingId(bookingId)
                .issuedByUserId(actor.id())
                .status(DeviceCommandStatus.PENDING)
                .timeoutAt(now.plusSeconds(commandTimeoutSeconds))
                .build());

        auditService.record(AuditEntry.of(AuditAction.REMOTE_COMMAND, "MACHINE", machineCode,
                        machine.getBranchId(), actor)
                .withReason(request.getReason())
                .withChange(Map.of("status", String.valueOf(machine.getStatus())),
                        Map.of("command", cmd, "commandId", commandId)));
        events.publishEvent(new DeviceCommandRequested(command.getId(),
                mqttTopicPayload(machine, cmd, commandId, bookingId, now)));
        log.info("[{}] Command {} queued by {} ({})", machineCode, cmd, actor.id(), actor.primaryRole());
        return toDto(command);
    }

    private DeviceCommandRequested.Payload mqttTopicPayload(Machine machine, String cmd, String commandId,
                                                            UUID bookingId, OffsetDateTime issuedAt) {
        return new DeviceCommandRequested.Payload(machine.getBranchId(), machine.getMachineCode(),
                buildCommandPayload(cmd, commandId, bookingId, issuedAt));
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

        // STOP is valid in any state except where nothing can stop.
        if (status == MachineStatus.OFFLINE || status == MachineStatus.COMPLETED) {
            throw new IllegalStateException("Cannot " + cmd + " machine in status: " + status);
        }
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