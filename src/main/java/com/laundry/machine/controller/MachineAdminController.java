package com.laundry.machine.controller;

import com.laundry.iot.service.DeviceProvisioningService;
import com.laundry.machine.dto.CreateMachineRequest;
import com.laundry.machine.dto.MachineDto;
import com.laundry.machine.dto.MachineQrLabelDto;
import com.laundry.machine.dto.MachineListFilter;
import com.laundry.machine.dto.PageResponse;
import com.laundry.machine.dto.UpdateMachineRequest;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.dto.CheckInResponse;
import com.laundry.machine.service.MachineService;
import com.laundry.machine.service.MachineStartAuthorizationService;
import org.springframework.security.core.Authentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Admin-only CRUD endpoints for machines.
 *
 * <p>Authorization is enforced by {@code SecurityConfig}: only
 * {@code ROLE_ADMIN} or {@code ROLE_OWNER} can reach these paths. The
 * controller does not re-check roles — keep auth in one place.
 *
 * <p>Lives in a separate class from {@link MachineController} (the public
 * read/command API) so that the class-level {@code @RequestMapping} does
 * not get prepended to the admin path.
 */
@RestController
@RequestMapping("/api/admin/machines")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Machines (Admin)", description = "Admin-only machine CRUD")
public class MachineAdminController {

    private final MachineRepository machineRepository;
    private final MachineService machineService;
    private final DeviceProvisioningService provisioningService;
    private final MachineStartAuthorizationService startAuthorizationService;

    @Value("${app.machine.qr-base-url:}")
    private String qrBaseUrl;

    @PostMapping
    @Operation(summary = "Create a new machine")
    public ResponseEntity<MachineDto> createMachine(@Valid @RequestBody CreateMachineRequest request) {
        MachineDto created = machineService.createMachine(request);
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping
    @Operation(summary = "Paged search across machines")
    public ResponseEntity<PageResponse<MachineDto>> listMachines(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        // Defensive enum parsing. Bad enum → 400 via IllegalArgumentException.
        MachineStatus statusFilter = parseEnum(MachineStatus.class, status, "status");
        MachineType typeFilter = parseEnum(MachineType.class, type, "type");

        // Admin UI uses 1-based pages; convert to 0-based for Spring Data.
        Pageable pageable = PageRequest.of(
                Math.max(0, page - 1),
                pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Machine> result = machineService.searchMachines(
                q, statusFilter, typeFilter, branchId, pageable);

        List<MachineDto> content = result.getContent().stream()
                .map(machineService::toDto)
                .toList();

        PageResponse<MachineDto> body = PageResponse.<MachineDto>builder()
                .content(content)
                .page(page)
                .pageSize(pageSize)
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .first(result.isFirst())
                .last(result.isLast())
                .build();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/qr-labels")
    @Operation(summary = "Get QR labels for all registered machines")
    public ResponseEntity<List<MachineQrLabelDto>> listMachineQrLabels() {
        if (qrBaseUrl == null || qrBaseUrl.isBlank()) {
            return ResponseEntity.status(503).build();
        }
        String baseUrl = qrBaseUrl.replaceAll("/+$", "");
        List<MachineQrLabelDto> labels = machineRepository.findAll(Sort.by("machineCode")).stream()
                .map(machine -> new MachineQrLabelDto(
                        machine.getMachineCode(),
                        machine.getDisplayName(),
                        machine.getType(),
                        baseUrl + "/machines/" + machine.getMachineCode()))
                .toList();
        return ResponseEntity.ok(labels);
    }

    @GetMapping("/{machineCode}")
    @Operation(summary = "Get a single machine by code")
    public ResponseEntity<MachineDto> getMachine(@PathVariable String machineCode) {
        return machineRepository.findByMachineCode(machineCode)
                .map(m -> ResponseEntity.ok(machineService.toDto(m)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{machineCode}")
    @Operation(summary = "Partially update a machine")
    public ResponseEntity<MachineDto> updateMachine(
            @PathVariable String machineCode,
            @Valid @RequestBody UpdateMachineRequest request) {
        return ResponseEntity.ok(machineService.updateMachine(machineCode, request));
    }

    @DeleteMapping("/{machineCode}")
    @Operation(summary = "Hard-delete a machine")
    public ResponseEntity<Void> deleteMachine(@PathVariable String machineCode) {
        // Revoke the bound ESP32's EMQX identity first so it cannot outlive the machine.
        provisioningService.unpair(machineCode);
        machineService.deleteMachine(machineCode);
        return ResponseEntity.noContent().build();
    }

    // ── Test run: unlock physical START without booking / QR ───────────────

    @PostMapping("/{machineCode}/test-start")
    @Operation(summary = "Admin test run: send AUTHORIZE_START without a booking or QR check-in")
    public ResponseEntity<CheckInResponse> testStart(@PathVariable String machineCode, Authentication authentication) {
        return ResponseEntity.ok(startAuthorizationService.adminTestStart(machineCode, userId(authentication)));
    }

    @GetMapping("/{machineCode}/test-start")
    @Operation(summary = "Latest admin test-run authorization for the machine")
    public ResponseEntity<CheckInResponse> latestTestStart(@PathVariable String machineCode) {
        return startAuthorizationService.latestTestStart(machineCode)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/{machineCode}/test-start/press")
    @Operation(summary = "Simulate pressing the physical START button during an admin test run")
    public ResponseEntity<Void> pressTestStart(@PathVariable String machineCode) {
        startAuthorizationService.pressTestStart(machineCode);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{machineCode}/test-start/cancel")
    @Operation(summary = "Cancel the active admin test-run authorization")
    public ResponseEntity<Void> cancelTestStart(@PathVariable String machineCode) {
        startAuthorizationService.cancelTestStart(machineCode);
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(Authentication authentication) {
        try {
            return authentication == null ? null : UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parse an enum query parameter; return null for blank inputs.
     * Throws IllegalArgumentException (→ 400) for non-blank unknown values.
     */
    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String paramName) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid value for '" + paramName + "': '" + raw + "'. Allowed: "
                            + Arrays.toString(type.getEnumConstants()));
        }
    }
}
