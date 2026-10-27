package com.laundry.machine.service;


import com.laundry.shared.error.ErrorCode;

import com.laundry.shared.error.BusinessException;

import com.laundry.branch.repository.BranchRepository;
import com.laundry.machine.dto.CreateMachineRequest;
import com.laundry.machine.dto.MachineDto;
import com.laundry.machine.dto.UpdateMachineRequest;
import com.laundry.machine.entity.*;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.machine.repository.DeviceEventRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.runtime.MachineRuntimeState;
import com.laundry.machine.runtime.MachineRuntimeStateService;
import com.laundry.shared.exception.DuplicateResourceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

/**
 * The machine catalog of the management web (FR-OPS-06): add, edit, retire and search machines and build
 * their DTO with the live runtime snapshot. Device traffic (status, heartbeat, events, ACKs) and the
 * offline/timeout jobs stay in {@link MachineService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MachineCatalogService {

    private final MachineRepository machineRepository;
    private final BranchRepository branchRepository;
    private final MachineRuntimeStateService runtimeStateService;
    private final MachineStateService machineStateService;
    private final MachineWebSocketBroadcaster broadcaster;
    private final DeviceEventRepository deviceEventRepository;

// ── Admin CRUD ─────────────────────────────────────────────────────────

    /**
     * Create a new machine. Throws {@link DuplicateResourceException} if the
     * machineCode is already taken within the same branch.
     *
     * <p>Newly created machines start as OFFLINE — they have never sent a
     * heartbeat. The first {@code processHeartbeat} call flips them to
     * AVAILABLE once the device connects.
     */
    @Transactional
    public MachineDto createMachine(CreateMachineRequest request) {
        String code = request.getMachineCode().trim().toUpperCase();

        if (request.getBranchId() == null) {
            throw new IllegalArgumentException("branchId is required — MQTT topics are scoped per branch");
        }
        UUID branchId = request.getBranchId();

        if (branchRepository != null && !branchRepository.existsById(branchId)) {
            throw new IllegalArgumentException("Branch not found with ID: " + branchId);
        }

        if (machineRepository.existsByBranchIdAndMachineCode(branchId, code)) {
            throw new DuplicateResourceException(
                    "Machine code already exists for branch " + branchId + ": " + code);
        }

        Machine machine = Machine.builder()
                .machineCode(code)
                .type(request.getType())
                .branchId(branchId)
                .displayName(request.getDisplayName())
                .locationDescription(request.getLocationDescription())
                .capacityKg(request.getCapacityKg())
                .status(MachineStatus.AVAILABLE)
                .deploymentStatus(MachineDeploymentStatus.UNASSIGNED)
                .online(false)
                .errorCode("")
                .build();
        machine = machineRepository.save(machine);

        persistEvent(machine, DeviceEventType.MACHINE_REGISTERED,
                "Machine registered by admin: " + branchId + "/" + code, null);
        log.info("[{}/{}] Machine created", branchId, code);
        return toDto(machine);
    }

/**
     * Apply a partial update to a machine. All fields in
     * {@link UpdateMachineRequest} are optional — only non-null fields are
     * applied. Status transitions are validated through
     * {@link MachineStateService}; an invalid transition throws
     * {@link IllegalStateException} (mapped to 400 by the global handler).
     *
     * <p>machineCode is intentionally NOT mutable here. Changing the code would
     * orphan all in-flight MQTT topics and require device re-provisioning.
     */
    @Transactional
    public MachineDto updateMachine(String machineCode, UpdateMachineRequest request) {
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> MachineNotFoundException.byCode(machineCode));

        boolean movesMachine = request.getBranchId() != null
                && !request.getBranchId().equals(machine.getBranchId());
        boolean changesType = request.getType() != null && request.getType() != machine.getType();
        if (movesMachine || changesType) {
            machine = machineRepository.findByIdForReservation(machine.getId())
                    .orElseThrow(() -> MachineNotFoundException.byCode(machineCode));
            if (machineRepository.hasActiveBookingAllocation(machine.getId())) {
                throw BusinessException.of(ErrorCode.CONFIG_CONFLICT, "Cannot reassign or change the type of a machine with active or future bookings");
            }
        }

        boolean changed = false;

        if (request.getType() != null && request.getType() != machine.getType()) {
            machine.setType(request.getType());
            changed = true;
        }
        if (request.getBranchId() != null && !request.getBranchId().equals(machine.getBranchId())) {
            if (branchRepository != null && !branchRepository.existsById(request.getBranchId())) {
                throw new IllegalArgumentException("Branch not found with ID: " + request.getBranchId());
            }
            machine.setBranchId(request.getBranchId());
            changed = true;
        }
        if (request.getDisplayName() != null && !request.getDisplayName().equals(machine.getDisplayName())) {
            machine.setDisplayName(request.getDisplayName());
            changed = true;
        }
        if (request.getLocationDescription() != null
                && !request.getLocationDescription().equals(machine.getLocationDescription())) {
            machine.setLocationDescription(request.getLocationDescription());
            changed = true;
        }
        if (request.getCapacityKg() != null
                && request.getCapacityKg().compareTo(
                        machine.getCapacityKg() == null ? java.math.BigDecimal.ZERO : machine.getCapacityKg()) != 0) {
            machine.setCapacityKg(request.getCapacityKg());
            changed = true;
        }
        if (request.getStatus() != null && request.getStatus() != machine.getStatus()) {
            if (!machineStateService.isValidTransition(machine.getStatus(), request.getStatus())) {
                throw new IllegalStateException(String.format(
                        "Invalid status transition: %s → %s", machine.getStatus(), request.getStatus()));
            }
            MachineStatus previous = machine.getStatus();
            machine.setStatus(request.getStatus());
            changed = true;
            persistEvent(machine, DeviceEventType.STATUS_CHANGED,
                    "Admin forced status: " + previous + " → " + request.getStatus(), null);
        }

        if (!changed) {
            return toDto(machine); // no-op; still 200 with current state
        }
        machine = machineRepository.save(machine);
        broadcaster.broadcastMachineUpdate(machine);
        log.info("[{}] Machine updated by admin", machineCode);
        return toDto(machine);
    }

/**
     * Hard-delete a machine. The runtime snapshot is also dropped.
     */
    @Transactional
    public void deleteMachine(String machineCode) {
        Machine machine = machineRepository.findByMachineCode(machineCode)
                .orElseThrow(() -> MachineNotFoundException.byCode(machineCode));
        UUID id = machine.getId();
        machineRepository.delete(machine);
        machineRepository.deleteRuntimeSnapshot(id);
        runtimeStateService.forget(id);
        log.warn("[{}] Machine deleted by admin", machineCode);
    }

/**
     * Paged admin search.
     */
    @Transactional(readOnly = true)
    public Page<Machine> searchMachines(String q, MachineStatus status, MachineType type,
                                        UUID branchId, Pageable pageable) {
        String trimmedQ = (q == null || q.isBlank()) ? null : q.trim();
        Pageable sorted = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return machineRepository.searchAdmin(trimmedQ, status, type, branchId, sorted);
    }

/** Machines of one branch with their live runtime snapshot (booking configuration screen). */
    @Transactional(readOnly = true)
    public List<MachineDto> listByBranch(UUID branchId) {
        return machineRepository.findByBranchId(branchId).stream().map(this::toDto).toList();
    }

/**
     * Build a {@link MachineDto} aggregated with the latest runtime snapshot
     * (if any). Static fields come from the entity; dynamic fields come from
     * {@link MachineRuntimeStateService}. Falls back to persisted JSONB if
     * the in-memory cache is cold (post-restart).
     */
    @Transactional(readOnly = true)
    public MachineDto toDto(Machine m) {
        MachineRuntimeState runtime = runtimeStateService.get(m.getId())
                .or(() -> runtimeStateService.loadPersisted(m.getId()))
                .orElse(null);

        MachineDto dto = MachineDto.builder()
                .id(m.getId())
                .machineCode(m.getMachineCode())
                .branchId(m.getBranchId())
                .type(m.getType())
                .status(m.getStatus())
                .deploymentStatus(m.getDeploymentStatus())
                .deviceId(m.getDeviceId())
                .online(m.isOnline() || (runtime != null && Boolean.TRUE.equals(runtime.getOnline())))
                .lastHeartbeatAt(m.getLastHeartbeatAt())
                .runtime(runtime)
                .errorCode(m.getErrorCode())
                .displayName(m.getDisplayName())
                .locationDescription(m.getLocationDescription())
                .capacityKg(m.getCapacityKg())
                .createdAt(m.getCreatedAt())
                .updatedAt(m.getUpdatedAt())
                .build();
        // Every caller (public machine page, customer orders, admin) gets the
        // live phase / remaining time at the top level, not only inside runtime.
        dto.flattenRuntime();
        return dto;
    }

@Transactional(readOnly = true)
    public Optional<MachineDto> toDetailDto(String machineCode) {
        return machineRepository.findByMachineCode(machineCode).map(this::toDto);
    }

    private void persistEvent(Machine machine, DeviceEventType type, String detail, UUID bookingId) {
        deviceEventRepository.save(DeviceEvent.of(machine, type, detail, bookingId));
    }
}
