package com.laundry.machine.service;



import com.laundry.shared.scheduling.JobLock;
import com.laundry.machine.entity.*;
import com.laundry.machine.exception.MachineNotFoundException;
import com.laundry.machine.repository.DeviceCommandRepository;
import com.laundry.machine.repository.DeviceEventRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.runtime.MachineRuntimeState;
import com.laundry.machine.runtime.MachineRuntimeStateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Core service for all machine-related business logic triggered by IoT events.
 *
 * <p>All MQTT message handlers delegate to this service. The service:
 * <ul>
 *     <li>Validates machine exists (unknown machines are logged and ignored).</li>
 *     <li>Validates state transitions.</li>
 *     <li>Updates persistent machine state in PostgreSQL <strong>only</strong>
 *         when the persistent state actually changes (online flip, status
 *         transition, error code change). High-frequency telemetry does
 *         NOT touch the {@code machines} table — it lives in the runtime
 *         snapshot managed by {@link MachineRuntimeStateService}.</li>
 *     <li>Persists significant events (state changes, leak detections,
 *         offline detection, command ACK, …) to {@code device_events}.</li>
 *     <li>Triggers WebSocket broadcasts (status updates + telemetry stream).</li>
 * </ul>
 *
 * <p>MQTT traffic is keyed by {@code (branchId-UUID, machineCode)}. Each
 * incoming message resolves the {@link Machine} entity exactly once per
 * handler call.
 */
@Service
@Slf4j
public class MachineService {

    private final MachineRepository machineRepository;
    private final DeviceEventRepository deviceEventRepository;
    private final DeviceCommandRepository deviceCommandRepository;
    private final MachineStateService machineStateService;
    private final MachineWebSocketBroadcaster broadcaster;
    private final MachineRuntimeStateService runtimeStateService;
    private final com.laundry.booking.service.BookingLifecycleService bookingLifecycleService;
    private final JobLock jobLock;

    @Autowired
    public MachineService(
            MachineRepository machineRepository,
            DeviceEventRepository deviceEventRepository,
            DeviceCommandRepository deviceCommandRepository,
            MachineStateService machineStateService,
            MachineWebSocketBroadcaster broadcaster,
            MachineRuntimeStateService runtimeStateService,
            com.laundry.booking.service.BookingLifecycleService bookingLifecycleService,
            JobLock jobLock) {
        this.machineRepository = machineRepository;
        this.deviceEventRepository = deviceEventRepository;
        this.deviceCommandRepository = deviceCommandRepository;
        this.machineStateService = machineStateService;
        this.broadcaster = broadcaster;
        this.runtimeStateService = runtimeStateService;
        this.bookingLifecycleService = bookingLifecycleService;
        this.jobLock = jobLock;
    }

    @Value("${app.machine.heartbeat-timeout-seconds:30}")
    private int heartbeatTimeoutSeconds;

    @Value("${app.machine.command-timeout-seconds:15}")
    private int commandTimeoutSeconds;

    @Value("${app.machine.offline-detector-batch:50}")
    private int offlineDetectorBatchSize;

    @Value("${app.machine.command-timeout-batch:100}")
    private int commandTimeoutBatchSize;

    
    public Optional<Machine> findByBranchIdAndMachineCode(UUID branchId, String machineCode) {
        return machineRepository.findByBranchIdAndMachineCode(branchId, machineCode);
    }

    public Machine requireMachine(UUID branchId, String machineCode) {
        return machineRepository.findByBranchIdAndMachineCode(branchId, machineCode)
                .orElseThrow(() -> {
                    log.warn("Unknown MQTT machine: branchId={} machineId={}", branchId, machineCode);
                    return new MachineNotFoundException(
                            String.format("Unknown MQTT machine: branchId=%s machineId=%s", branchId, machineCode));
                });
    }

    // ── /status — runtime snapshot → persistent state ──────────────────────

    /**
     * Promote a runtime status snapshot to a persistent state change.
     *
     * <p>This is the only place a {@code Machine} row is updated by MQTT
     * status messages. Persisted only when {@code status} transitions.
     * Sensor fields live in the runtime snapshot, never on the entity.
     */
    @Transactional
    public void processStatus(UUID branchId, String machineCode, MachineRuntimeState snapshot) {
        if (snapshot == null) return;

        Optional<Machine> machineOpt = machineRepository.findByBranchIdAndMachineCode(branchId, machineCode);
        if (machineOpt.isEmpty()) {
            log.warn("Unknown MQTT machine: branchId={} machineId={}", branchId, machineCode);
            return;
        }
        Machine machine = machineOpt.get();
        boolean persistentStateChanged = false;
        MachineStatus previousStatus = machine.getStatus();

        if (snapshot.getStatus() != null && !snapshot.getStatus().isBlank()) {
            boolean statusChanged = machineStateService.applyDeviceStatus(machine, snapshot.getStatus());
            if (statusChanged) {
                persistentStateChanged = true;
                persistEvent(machine, DeviceEventType.STATUS_CHANGED,
                        "Status: " + previousStatus + " → " + machine.getStatus(), null);
                // A cycle ended (COMPLETED, or straight back to AVAILABLE if that message was lost).
                boolean wasRunning = previousStatus == MachineStatus.RUNNING || previousStatus == MachineStatus.PAUSED;
                boolean nowIdle = machine.getStatus() == MachineStatus.COMPLETED
                        || machine.getStatus() == MachineStatus.AVAILABLE;
                if (wasRunning && nowIdle) {
                    bookingLifecycleService.onMachineCycleFinished(machine.getId());
                }
            }
        }

        String errMsg = snapshot.getErrorMessage();
        // Back to AVAILABLE (RESET / clean STOP) means the old fault is gone, even from firmware that omits "error".
        if (errMsg == null && machine.getStatus() == MachineStatus.AVAILABLE) errMsg = "";
        if (errMsg != null && !errMsg.equals(machine.getErrorCode())) {
            machine.setErrorCode(errMsg);
            persistentStateChanged = true;
        }

        boolean wasOffline = !machine.isOnline();
        if (wasOffline) {
            machine.setOnline(true);
            persistentStateChanged = true;
        }

        if (Boolean.TRUE.equals(snapshot.getLeakDetected())) {
            persistEvent(machine, DeviceEventType.LEAK_DETECTED,
                    "Water leak detected by device", null);
        }

        OffsetDateTime now = OffsetDateTime.now();
        machine.setLastHeartbeatAt(now);

        if (persistentStateChanged) {
            machineRepository.save(machine);
        } else {
            machineRepository.updateHeartbeat(machine.getId(), now);
        }

        broadcaster.broadcastMachineUpdate(machine,
                snapshot.getDoorClosed(),
                snapshot.getLeakDetected());
    }

    /**
     * Same as {@link #processStatus} but driven by telemetry — used when
     * telemetry carries a meaningful business status (e.g. {@code "WASHING"}).
     */
    @Transactional
    public void processStatusFromRuntime(UUID branchId, String machineCode, MachineRuntimeState snapshot) {
        if (snapshot == null || snapshot.getStatus() == null || snapshot.getStatus().isBlank()) return;
        processStatus(branchId, machineCode, snapshot);
    }

    /**
     * Mark a machine offline from a Last Will retained message
     * ({@code "online": false} or {@code "status": "OFFLINE"}).
     *
     * <p>Keeps the latest sensor data intact; only flips the runtime
     * snapshot to {@code online=false} and updates {@code machines.online}.
     */
    @Transactional
    public void markOfflineFromMqtt(UUID branchId, String machineCode, String status, String errorMessage) {
        Optional<Machine> machineOpt = machineRepository.findByBranchIdAndMachineCode(branchId, machineCode);
        if (machineOpt.isEmpty()) return;
        Machine machine = machineOpt.get();

        MachineRuntimeState existing = runtimeStateService.get(machine.getId()).orElse(null);
        runtimeStateService.markOffline(machine.getId(), machine);

        boolean persistentChanged = false;
        if (machine.isOnline()) {
            machine.setOnline(false);
            persistentChanged = true;
        }
        if (status != null && !status.isBlank()
                && machineStateService.isValidTransition(machine.getStatus(), MachineStatus.OFFLINE)) {
            machine.setStatus(MachineStatus.OFFLINE);
            machine.setErrorCode(errorMessage != null ? errorMessage : machine.getErrorCode());
            persistentChanged = true;
            persistEvent(machine, DeviceEventType.OFFLINE_DETECTED,
                    "MQTT Last Will received: status=" + status, null);
        }

        if (persistentChanged) {
            machine.setLastHeartbeatAt(OffsetDateTime.now());
            machineRepository.save(machine);
        }
        broadcaster.broadcastMachineUpdate(machine);
        if (existing != null) {
            broadcaster.broadcastTelemetry(machineCode,
                    existing.getWeightKg(), existing.getWaterLevelLiters(),
                    existing.getVibrationG(), existing.getRemainingSeconds());
        }
    }

    // ── /telemetry — runtime snapshot only, NO DB write on hot path ─────────

    /**
     * Push realtime telemetry over WebSocket. The runtime snapshot was
     * already updated by {@link MachineRuntimeStateService#applyTelemetry};
     * here we just bridge it to STOMP.
     */
    public void broadcastTelemetry(UUID branchId, String machineCode, MachineRuntimeState snapshot) {
        if (snapshot == null) return;
        broadcaster.broadcastTelemetry(
                machineCode,
                snapshot.getWeightKg(),
                snapshot.getWaterLevelLiters(),
                snapshot.getVibrationG(),
                snapshot.getRemainingSeconds());
    }

    // ── /heartbeat — single bulk UPDATE per heartbeat ──────────────────────

    /**
     * Update {@code machines.online} + {@code last_heartbeat_at} via a
     * single bulk JPQL UPDATE. When the device is reconnecting
     * (was offline), we additionally flip the entity and broadcast.
     */
    @Transactional
    public void processHeartbeat(UUID branchId, String machineCode, MachineRuntimeState snapshot) {
        Optional<Machine> machineOpt = machineRepository.findByBranchIdAndMachineCode(branchId, machineCode);
        if (machineOpt.isEmpty()) {
            log.warn("Unknown MQTT machine: branchId={} machineId={}", branchId, machineCode);
            return;
        }
        Machine machine = machineOpt.get();
        OffsetDateTime now = OffsetDateTime.now();

        // Always: refresh last_heartbeat_at via bulk UPDATE.
        machineRepository.updateHeartbeat(machine.getId(), now);

        boolean wasOffline = !machine.isOnline();
        if (wasOffline) {
            machine.setOnline(true);
            machine.setLastHeartbeatAt(now);
            machineRepository.save(machine);
            persistEvent(machine, DeviceEventType.CONNECTION_RECOVERED,
                    "Machine came back online", null);
            broadcaster.broadcastMachineUpdate(machine);
            log.info("[{}/{}] Machine came back online", branchId, machineCode);
        }
    }

    // ── /event — audit only, NO Machine.status write ───────────────────────

    /**
     * Persist an audit event for the given machine. Events are NOT used to
     * drive {@code Machine.status} — current state always comes from
     * /telemetry or /status.
     *
     * <p>Safety errors (DOOR OPEN, LEAK DETECTED, OVERLOAD, EXCESSIVE
     * VIBRATION) become error events with {@code errorMessage} attached.
     */
    @Transactional
    public void processDeviceEvent(UUID branchId, String machineCode,
                                   MachineRuntimeState snapshot,
                                   String rawEventType, String detail, String bookingIdStr) {
        Optional<Machine> machineOpt = machineRepository.findByBranchIdAndMachineCode(branchId, machineCode);
        if (machineOpt.isEmpty()) {
            log.warn("Unknown MQTT machine: branchId={} machineId={}", branchId, machineCode);
            return;
        }
        Machine machine = machineOpt.get();

        DeviceEventType type = parseEventType(rawEventType, machineCode);
        if (type == null) return;

        UUID bookingId = null;
        if (bookingIdStr != null && !bookingIdStr.isBlank()) {
            try {
                bookingId = UUID.fromString(bookingIdStr);
            } catch (IllegalArgumentException e) {
                log.warn("[{}/{}] Invalid bookingId in event payload: '{}'", branchId, machineCode, bookingIdStr);
            }
        }

        boolean persistentChanged = false;

        // Update online + heartbeat timestamp (cheap, no full save).
        machineRepository.updateHeartbeat(machine.getId(), OffsetDateTime.now());
        if (!machine.isOnline()) {
            machine.setOnline(true);
            persistentChanged = true;
        }

        // Safety errors → record on the entity but DO NOT switch status to ERROR
        // from a heartbeat-style event payload. /telemetry is the source of truth.
        if (snapshot != null && snapshot.getErrorMessage() != null) {
            String msg = snapshot.getErrorMessage();
            if (!msg.equals(machine.getErrorCode())) {
                machine.setErrorCode(msg);
                persistentChanged = true;
            }
        }

        if (persistentChanged) {
            machineRepository.save(machine);
            broadcaster.broadcastMachineUpdate(machine);
        }

        persistEvent(machine, type, detail, bookingId);
        log.info("[{}/{}] Device event: {} — {}", branchId, machineCode, type, detail);
    }

    // ── /command/ack — match by commandId/requestId ────────────────────────

    /**
     * Match an ACK to its in-flight {@link DeviceCommand}.
     *
     * <p>Firmware v2 sends {@code requestId}; v1 sends {@code commandId}.
     * Either is accepted.
     */
    @Transactional
    public void processCommandAck(UUID branchId, String machineCode,
                                  String correlationId, boolean accepted,
                                  String result, String message, String reason) {
        if (correlationId == null || correlationId.isBlank()) {
            log.warn("[{}/{}] Command ACK received without commandId/requestId — ignoring",
                    branchId, machineCode);
            return;
        }

        Optional<DeviceCommand> cmdOpt = deviceCommandRepository.findByCommandId(correlationId);
        if (cmdOpt.isEmpty()) {
            log.warn("[{}/{}] No command found with commandId/requestId '{}' — may be stale ACK",
                    branchId, machineCode, correlationId);
            return;
        }

        DeviceCommand cmd = cmdOpt.get();
        OffsetDateTime now = OffsetDateTime.now();

        cmd.setStatus(accepted ? DeviceCommandStatus.ACKNOWLEDGED : DeviceCommandStatus.REJECTED);
        cmd.setAckResult(result != null ? result : (accepted ? "ACCEPTED" : "REJECTED"));
        cmd.setAckReason(reason != null ? reason : message);
        cmd.setAcknowledgedAt(now);
        deviceCommandRepository.save(cmd);

        final String detail = "Command: " + cmd.getCommand()
                + ", result: " + cmd.getAckResult()
                + (cmd.getAckReason() != null ? ", reason: " + cmd.getAckReason() : "");
        machineRepository.findByBranchIdAndMachineCode(branchId, machineCode)
                .ifPresent(machine -> persistEvent(machine,
                        accepted ? DeviceEventType.COMMAND_ACKNOWLEDGED : DeviceEventType.COMMAND_REJECTED,
                        detail, cmd.getBookingId()));

        log.info("[{}/{}] Command ACK processed: commandId={}, accepted={}, status={}",
                branchId, machineCode, correlationId, accepted, cmd.getStatus());
    }

    // ── Offline detector scheduler ────────────────────────────────────────

    /**
     * Scheduled job: detect machines that haven't sent a heartbeat within
     * the configured threshold (default 30 s — i.e. three missed 10 s
     * heartbeats). Marks them offline AND broadcasts the change.
     */
    @Scheduled(fixedDelayString = "${app.machine.offline-detector-interval-ms:10000}")
    @Transactional
    public void detectOfflineMachines() {
        if (!jobLock.tryLockForTransaction("machine-offline-detector")) {
            return;
        }
        long startNs = System.nanoTime();
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(heartbeatTimeoutSeconds);

        List<UUID> candidateIds = machineRepository.findStaleOnlineMachineIds(threshold,
                offlineDetectorBatchSize);
        if (candidateIds.isEmpty()) {
            return;
        }

        OffsetDateTime ts = OffsetDateTime.now();
        int updated = machineRepository.markOfflineIfStale(
                candidateIds, ts, threshold);

        if (updated == 0) {
            log.debug("Offline detector: race lost for {} candidate(s) (fresh heartbeats)",
                    candidateIds.size());
            return;
        }

        List<Machine> flipped = machineRepository.findAllById(candidateIds);

        List<DeviceEvent> events = new ArrayList<>(flipped.size());
        for (Machine m : flipped) {
            runtimeStateService.markOffline(m.getId(), m);
            events.add(DeviceEvent.builder()
                    .machineId(m.getId())
                    .machineCode(m.getMachineCode())
                    .eventType(DeviceEventType.OFFLINE_DETECTED)
                    .detail("No heartbeat for " + heartbeatTimeoutSeconds + "s")
                    .build());
        }
        if (!events.isEmpty()) {
            deviceEventRepository.saveAll(events);
        }
        for (Machine m : flipped) {
            broadcaster.broadcastMachineUpdate(m);
            log.warn("[{}] Machine marked OFFLINE (no heartbeat for {}s)",
                    m.getMachineCode(), heartbeatTimeoutSeconds);
        }

        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        log.info("Offline detector: scanned={}, marked_offline={}, duration_ms={}",
                candidateIds.size(), updated, durationMs);
    }

    // ── Command timeout scheduler ──────────────────────────────────────────

    /**
     * Scheduled job: detect commands that have been SENT but not ACKed
     * within the timeout window.
     */
    @Scheduled(fixedDelayString = "${app.machine.command-timeout-interval-ms:5000}")
    @Transactional
    public void detectCommandTimeouts() {
        if (!jobLock.tryLockForTransaction("device-command-timeout")) {
            return;
        }
        long startNs = System.nanoTime();
        OffsetDateTime now = OffsetDateTime.now();

        List<UUID> candidateIds = deviceCommandRepository
                .findStaleSentCommandIds(now, commandTimeoutBatchSize);
        if (candidateIds.isEmpty()) {
            return;
        }

        int updated = deviceCommandRepository.markSentAsTimedOut(
                candidateIds, DeviceCommandStatus.TIMEOUT, now);
        if (updated == 0) {
            log.debug("Command timeout detector: race lost for {} candidate(s)", candidateIds.size());
            return;
        }

        List<DeviceCommand> timedOut = deviceCommandRepository.findTimedOutBatchMetadata(candidateIds);

        List<UUID> machineIds = timedOut.stream()
                .map(DeviceCommand::getMachineId)
                .distinct()
                .toList();
        Map<UUID, Machine> machinesById = machineRepository.findAllById(machineIds).stream()
                .collect(Collectors.toMap(Machine::getId, Function.identity()));

        List<DeviceEvent> events = new ArrayList<>(timedOut.size());
        for (DeviceCommand cmd : timedOut) {
            Machine machine = machinesById.get(cmd.getMachineId());
            if (machine == null) {
                continue;
            }
            events.add(DeviceEvent.builder()
                    .machineId(machine.getId())
                    .machineCode(machine.getMachineCode())
                    .eventType(DeviceEventType.COMMAND_TIMEOUT)
                    .detail("Command " + cmd.getCommand() + " (id=" + cmd.getCommandId() + ") timed out after " +
                            commandTimeoutSeconds + "s")
                    .bookingId(cmd.getBookingId())
                    .build());
        }
        if (!events.isEmpty()) {
            deviceEventRepository.saveAll(events);
        }

        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        log.warn("Command timeout detector: scanned={}, timed_out={}, duration_ms={}",
                candidateIds.size(), updated, durationMs);
        for (DeviceCommand cmd : timedOut) {
            log.warn("[{}] Command TIMEOUT: commandId={}, command={}",
                    cmd.getMachineCode(), cmd.getCommandId(), cmd.getCommand());
        }
    }

    
    
    
    
    
    
    
    // ── Private helpers ────────────────────────────────────────────────────

    private void persistEvent(Machine machine, DeviceEventType type, String detail, UUID bookingId) {
        deviceEventRepository.save(DeviceEvent.of(machine, type, detail, bookingId));
    }

    private DeviceEventType parseEventType(String raw, String machineCode) {
        if (raw == null || raw.isBlank()) return null;
        if ("ERROR".equalsIgnoreCase(raw.trim())) return DeviceEventType.DEVICE_ERROR;
        try {
            return DeviceEventType.valueOf(raw.toUpperCase().trim());
        } catch (IllegalArgumentException e) {
            log.warn("[{}] Unknown event type '{}' — ignoring", machineCode, raw);
            return null;
        }
    }
}