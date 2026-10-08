package com.laundry.machine.controller;

import com.laundry.machine.dto.*;
import com.laundry.machine.entity.*;
import com.laundry.machine.repository.DeviceCommandRepository;
import com.laundry.machine.repository.DeviceEventRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.DeviceCommandService;
import com.laundry.machine.service.MachineService;
import com.laundry.machine.service.MachineStartAuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API for machine management and IoT command dispatch.
 *
 * <p>All command endpoints authenticate via JWT. The frontend MUST NOT publish
 * directly to EMQX — all commands route through this API for validation.
 */
@RestController
@RequestMapping("/api/machines")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Machines", description = "Machine status, telemetry, events, and command APIs")
public class MachineController {

    private final MachineRepository machineRepository;
    private final DeviceEventRepository deviceEventRepository;
    private final DeviceCommandRepository deviceCommandRepository;
    private final DeviceCommandService deviceCommandService;
    private final MachineService machineService;
    private final MachineStartAuthorizationService startAuthorizationService;

    /**
     * Base URL for static machine QR codes.
     * Configured via {@code app.machine.qr-base-url} (env: {@code QR_BASE_URL}).
     * If empty, the QR endpoint returns 503.
     */
    @Value("${app.machine.qr-base-url:}")
    private String qrBaseUrl;

    // ── Machine listing ───────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List all machines with current status")
    public ResponseEntity<List<MachineDto>> getAllMachines() {
        List<MachineDto> machines = machineRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(machines);
    }

    @GetMapping("/{machineCode}")
    @Operation(summary = "Get machine details by machine code (aggregates static config + latest runtime snapshot)")
    public ResponseEntity<MachineDto> getMachine(@PathVariable String machineCode) {
        return machineService.toDetailDto(machineCode)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Static QR code content for a machine.
     *
     * <p>The QR encodes a URL: {@code <qrBaseUrl>/machines/<machineCode>}.
     * No token, no expiry — the QR is valid as long as the machine exists.
     * The frontend or any QR generator uses this to produce a printable QR label.
     *
     * <p>Scanning the QR does NOT start the machine. The landing page should:
     * <ol>
     *   <li>Show machine status (GET /api/machines/{machineCode}/status).
     *   <li>Present a "Start" button that calls POST /api/machines/{machineCode}/commands
     *       with the user's bookingId — fully authenticated.
     * </ol>
     *
     * <p>If {@code qrBaseUrl} is not configured, returns HTTP 503.
     */
    @GetMapping("/{machineCode}/qr")
    @Operation(summary = "Get static QR code content for a machine (no token, no expiry)")
    public ResponseEntity<MachineQrResponse> getMachineQr(@PathVariable String machineCode) {
        if (qrBaseUrl == null || qrBaseUrl.isBlank()) {
            log.warn("QR base URL not configured — returning 503 for machine {}", machineCode);
            return ResponseEntity.status(503).build();
        }

        return machineRepository.findByMachineCode(machineCode)
                .map(machine -> {
                    String machinePageUrl = qrBaseUrl + "/machines/" + machine.getMachineCode();
                    return ResponseEntity.ok(MachineQrResponse.builder()
                            .machineCode(machine.getMachineCode())
                            .qrUrl(machinePageUrl)      // same URL — scanner decodes, browser navigates
                            .machinePageUrl(machinePageUrl)
                            .build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{machineCode}/status")
    @Operation(summary = "Get current machine status and latest runtime snapshot")
    public ResponseEntity<MachineDto> getMachineStatus(@PathVariable String machineCode) {
        return machineService.toDetailDto(machineCode)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ── QR check-in / Physical START authorization ─────────────────────────

    /**
     * Customer-facing check-in endpoint. Scan QR → POST machineCode →
     * backend validates reservation ownership + payment + machine
     * availability, creates a {@link MachineStartAuthorization}, and
     * publishes {@code AUTHORIZE_START} over MQTT.
     *
     * <p>The customer's identity is taken from Spring Security — the
     * frontend MUST NOT be allowed to submit a userId.
     */
    @PostMapping("/check-in")
    @Operation(summary = "QR check-in: validate reservation + payment, create START authorization, publish AUTHORIZE_START over MQTT")
    public ResponseEntity<CheckInResponse> checkIn(@Valid @RequestBody CheckInRequest request,
                                                  Authentication authentication) {
        UUID userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        CheckInResponse response = startAuthorizationService.checkIn(request.machineCode(), userId);
        return ResponseEntity.ok(response);
    }

    /**
     * Polling endpoint the customer app uses to know when the device has
     * acknowledged and the physical START is unlocked.
     *
     * <p>Strict ownership check — only the user who created the
     * authorization can read its status.
     */
    @GetMapping("/check-in/{authorizationId}")
    @Operation(summary = "Get current authorization status (customer polling)")
    public ResponseEntity<AuthorizationStatusResponse> getAuthorizationStatus(@PathVariable UUID authorizationId,
                                                                             Authentication authentication) {
        UUID userId = extractUserId(authentication);
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        return startAuthorizationService.findOwnedByUser(authorizationId, userId)
                .map(a -> ResponseEntity.ok(AuthorizationStatusResponse.builder()
                        .authorizationId(a.getId())
                        .reservationId(a.getReservationId())
                        .machineId(a.getMachineId())
                        .machineCode(a.getMachineCode())
                        .authorizationStatus(a.getStatus())
                        .createdAt(a.getCreatedAt())
                        .expiresAt(a.getExpiresAt())
                        .grantedAt(a.getGrantedAt())
                        .consumedAt(a.getConsumedAt())
                        .revokedAt(a.getRevokedAt())
                        .revokeReason(a.getRevokeReason())
                        .build()))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @GetMapping("/{machineCode}/events")
    @Operation(summary = "Get event history for a machine (paginated)")
    public ResponseEntity<Page<DeviceEventDto>> getEvents(
            @PathVariable String machineCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return machineRepository.findByMachineCode(machineCode)
                .map(machine -> {
                    Page<DeviceEvent> events = deviceEventRepository
                            .findByMachineIdOrderByCreatedAtDesc(machine.getId(),
                                    PageRequest.of(page, size, Sort.by("createdAt").descending()));
                    return ResponseEntity.ok(events.map(this::toEventDto));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    @GetMapping("/{machineCode}/commands")
    @Operation(summary = "Get command history for a machine (paginated)")
    public ResponseEntity<Page<DeviceCommandDto>> getCommands(
            @PathVariable String machineCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return machineRepository.findByMachineCode(machineCode)
                .map(machine -> {
                    Page<DeviceCommand> commands = deviceCommandRepository
                            .findByMachineIdOrderByCreatedAtDesc(machine.getId(),
                                    PageRequest.of(page, size));
                    return ResponseEntity.ok(commands.map(this::toCmdDto));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Issue a command to a machine.
     *
     * <p>Security:
     * <ul>
     *   <li>User must be authenticated (JWT).
     *   <li>RESET: STAFF, MANAGER, ADMIN only (enforced in Phase 9).
     *   <li>START: authenticated customer with valid booking, or STAFF+.
     * </ul>
     *
     * <p>The command is validated, persisted, then published to EMQX.
     * The response includes the commandId for tracking ACK status.
     */
    @PostMapping("/{machineCode}/commands")
    @Operation(summary = "Issue a command to a machine (START, STOP, RESET, STATUS, TARE)")
    public ResponseEntity<DeviceCommandDto> issueCommand(
            @PathVariable String machineCode,
            @Valid @RequestBody DeviceCommandRequest request,
            Authentication authentication) {

        // Extract user ID from JWT principal (email → user lookup deferred to Phase 9)
        // For now, use a placeholder UUID — Phase 9 wires the real user lookup
        UUID userId = extractUserId(authentication);

        log.info("Command request: machineCode={}, command={}, user={}", machineCode, request.getCommand(), userId);

        DeviceCommandDto result = deviceCommandService.issueCommand(machineCode, request, userId);
        return ResponseEntity.ok(result);
    }

    // ── Admin CRUD (Slice 1) ─────────────────────────────────────────────────
    //
    // Admin endpoints live in {@code MachineAdminController} with its own
    // @RequestMapping("/api/admin/machines"). Keep them out of this class to
    // avoid the class-level "/api/machines" prefix getting prepended.

    // ── Mapper helpers ────────────────────────────────────────────────────────

    private MachineDto toDto(Machine m) {
        MachineDto dto = machineService.toDto(m);
        dto.flattenRuntime();
        return dto;
    }

    private DeviceEventDto toEventDto(DeviceEvent e) {
        return DeviceEventDto.builder()
                .id(e.getId())
                .machineId(e.getMachineId())
                .machineCode(e.getMachineCode())
                .eventType(e.getEventType())
                .detail(e.getDetail())
                .bookingId(e.getBookingId())
                .createdAt(e.getCreatedAt())
                .build();
    }

    private DeviceCommandDto toCmdDto(DeviceCommand c) {
        return DeviceCommandDto.builder()
                .id(c.getId())
                .commandId(c.getCommandId())
                .machineId(c.getMachineId())
                .machineCode(c.getMachineCode())
                .command(c.getCommand())
                .bookingId(c.getBookingId())
                .status(c.getStatus())
                .sentAt(c.getSentAt())
                .acknowledgedAt(c.getAcknowledgedAt())
                .ackResult(c.getAckResult())
                .ackReason(c.getAckReason())
                .timeoutAt(c.getTimeoutAt())
                .createdAt(c.getCreatedAt())
                .build();
    }

    /**
     * Extract authenticated user ID from the JWT principal.
     * The {@link com.laundry.auth.security.JwtAuthenticationFilter} puts
     * the JWT subject (which is the user UUID) into {@code Authentication.getName()}.
     */
    private UUID extractUserId(Authentication authentication) {
        if (authentication == null) return null;
        String principal = authentication.getName();
        if (principal == null || principal.isBlank()) return null;
        try {
            return UUID.fromString(principal.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Authenticated principal is not a UUID: {}", principal);
            return null;
        }
    }
}
