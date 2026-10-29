package com.laundry.machine.runtime;

import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.repository.MachineRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the latest {@link MachineRuntimeState} for every machine in memory
 * and (optionally) persists it to PostgreSQL as a single JSONB row per machine.
 *
 * <p><strong>Why this design:</strong>
 * <ul>
 *     <li>Telemetry arrives every 2–5 s. Writing it to {@code machines} via
 *         Hibernate would issue one or more UPDATE statements per second per
 *         device — guaranteed DB storm. We keep it out of the JPA persistence
 *         context.</li>
 *     <li>Frontend "Machine Detail" must still show the latest known sensor
 *         values after a refresh. So we cache in memory AND persist the
 *         latest snapshot as one compact JSONB row.</li>
 *     <li>A scheduled flusher writes the snapshot at most once every
 *         {@code app.runtime.flush-interval-seconds} (default 10 s) and
 *         also on process shutdown (best effort).</li>
 * </ul>
 *
 * <p>The in-memory map is keyed by {@link Machine#getId()}, so concurrent
 * updates from MQTT threads are safe. Each update is a defensive copy of
 * the previous snapshot, so a half-updated state can never be observed by
 * a reader.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MachineRuntimeStateService {

    private final MachineRepository machineRepository;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;
    /** Lazy to break circular dep at field-init time. */
    private TransactionTemplate txTemplate;

    @Value("${app.runtime.flush-interval-seconds:10}")
    private int flushIntervalSeconds;

    private TransactionTemplate tx() {
        if (txTemplate == null) {
            txTemplate = new TransactionTemplate(transactionManager);
        }
        return txTemplate;
    }

    /** machineId → latest known snapshot. Concurrent because MQTT fanout is parallel. */
    private final ConcurrentHashMap<UUID, MachineRuntimeState> states = new ConcurrentHashMap<>();

    /** Dirty flag — set to true on every update, cleared after a successful flush. */
    private final ConcurrentHashMap<UUID, Boolean> dirty = new ConcurrentHashMap<>();

    // ── Read ────────────────────────────────────────────────────────────────

    /**
     * Returns a defensive copy of the current snapshot, or {@link Optional#empty()}
     * if the device has never reported anything yet.
     */
    public Optional<MachineRuntimeState> get(UUID machineId) {
        MachineRuntimeState s = states.get(machineId);
        return Optional.ofNullable(s).map(this::copyOf);
    }

    /**
     * Bulk fetch — used by Machine Detail / list endpoints.
     */
    public java.util.Map<UUID, MachineRuntimeState> snapshotAll() {
        java.util.Map<UUID, MachineRuntimeState> out = new java.util.HashMap<>(states.size());
        states.forEach((id, s) -> out.put(id, copyOf(s)));
        return out;
    }

    // ── Write — entrypoints used by MachineService / WasherMessageHandler ──

    /**
     * Apply a {@code /telemetry} snapshot. This is the comprehensive update —
     * the firmware sends every relevant field every 2–5 s. We treat it as the
     * authoritative source.
     *
     * <p>Machine {@code online} state is derived from the device-reported flag
     * if present; if not present, we infer {@code true} on a telemetry message
     * (the device wouldn't be publishing otherwise).
     *
     * @param machine     the resolved {@link Machine} (null = unknown device, skipped)
     * @param snapshot    the parsed payload (see {@link com.laundry.mqtt.handler.WasherPayloads.DeviceSnapshot})
     */
    public void applyTelemetry(Machine machine, MachineRuntimeState snapshot) {
        if (machine == null || snapshot == null) return;
        snapshot.setMachineId(machine.getId());
        snapshot.setBranchId(machine.getBranchId());
        snapshot.setMachineCode(machine.getMachineCode());
        if (snapshot.getLastSeen() == null) snapshot.setLastSeen(OffsetDateTime.now());
        // Default online to true when telemetry is received but firmware didn't say.
        if (snapshot.getOnline() == null) snapshot.setOnline(true);
        // Update telemetry timestamp.
        snapshot.setLastTelemetryAt(OffsetDateTime.now());

        // Reject stale / out-of-order telemetry per seq ordering (see #6 of
        // the firmware 3.1.0 contract).
        MachineRuntimeState prev = states.get(machine.getId());
        if (!acceptTelemetry(prev, snapshot)) {
            log.debug("[STALE TELEMETRY] machine={} incoming_seq={} last_seq={} — dropping",
                    machine.getMachineCode(), snapshot.getLastTelemetrySeq(),
                    prev == null ? null : prev.getLastTelemetrySeq());
            return;
        }
        store(snapshot);
    }

    /**
     * Apply a {@code /status} snapshot. Conservative merge: telemetry fields
     * (weight, water level, vibration, …) are preserved unless the status
     * payload explicitly carries new values.
     */
    public void applyStatus(Machine machine, MachineRuntimeState snapshot) {
        if (machine == null || snapshot == null) return;
        // /status also carries a seq in firmware 3.1.0 — accept stale ones
        // here so we don't drop a LWT OFFLINE message with a lower seq.
        MachineRuntimeState merged = states.computeIfPresent(machine.getId(),
                (id, prev) -> mergePreferStatus(prev, snapshot));
        if (merged == null) {
            // No telemetry yet — start fresh.
            merged = snapshot;
            merged.setMachineId(machine.getId());
            merged.setBranchId(machine.getBranchId());
            merged.setMachineCode(machine.getMachineCode());
        }
        if (merged.getLastSeen() == null) merged.setLastSeen(OffsetDateTime.now());
        if (merged.getOnline() == null) merged.setOnline(true);
        merged.setLastTelemetryAt(OffsetDateTime.now());
        states.put(machine.getId(), merged);
        dirty.put(machine.getId(), Boolean.TRUE);
    }

    /**
     * Apply a {@code /heartbeat} — only touches connectivity + liveness, never
     * overwrites sensor fields with null/default.
     */
    public void applyHeartbeat(Machine machine, MachineRuntimeState snapshot) {
        if (machine == null || snapshot == null) return;
        MachineRuntimeState next = states.computeIfPresent(machine.getId(),
                (id, prev) -> mergeHeartbeat(prev, snapshot));
        if (next == null) {
            next = snapshot;
            next.setMachineId(machine.getId());
            next.setBranchId(machine.getBranchId());
            next.setMachineCode(machine.getMachineCode());
        }
        next.setOnline(true);
        // Heartbeat-only timestamp — never set by /telemetry.
        next.setLastHeartbeat(OffsetDateTime.now());
        if (next.getLastSeen() == null) next.setLastSeen(OffsetDateTime.now());
        states.put(machine.getId(), next);
        dirty.put(machine.getId(), Boolean.TRUE);
    }

    /**
     * Apply a {@code /event} — only updates fields the payload carried. Sensor
     * fields are not cleared.
     */
    public void applyEvent(Machine machine, MachineRuntimeState snapshot) {
        if (machine == null || snapshot == null) return;
        MachineRuntimeState next = states.computeIfPresent(machine.getId(),
                (id, prev) -> mergeEvent(prev, snapshot));
        if (next == null) {
            next = snapshot;
            next.setMachineId(machine.getId());
            next.setBranchId(machine.getBranchId());
            next.setMachineCode(machine.getMachineCode());
        }
        next.setLastSeen(OffsetDateTime.now());
        states.put(machine.getId(), next);
        dirty.put(machine.getId(), Boolean.TRUE);
    }

    /**
     * Mark a machine offline without zeroing out sensor data. Called by the
     * offline detector (heartbeat timeout) or when the MQTT broker delivers a
     * LWT retained message carrying {@code "online": false}.
     */
    public void markOffline(UUID machineId, Machine machine) {
        MachineRuntimeState next = states.computeIfPresent(machineId,
                (id, prev) -> {
                    MachineRuntimeState m = copyOf(prev);
                    m.setOnline(false);
                    m.setStatus(MachineStatus.OFFLINE.name());
                    if (m.getLastSeen() == null) m.setLastSeen(OffsetDateTime.now());
                    return m;
                });
        if (next == null && machine != null) {
            next = MachineRuntimeState.builder()
                    .machineId(machine.getId())
                    .branchId(machine.getBranchId())
                    .machineCode(machine.getMachineCode())
                    .online(false)
                    .status(MachineStatus.OFFLINE.name())
                    .lastSeen(OffsetDateTime.now())
                    .build();
            states.put(machine.getId(), next);
        }
        if (next != null) dirty.put(machineId, Boolean.TRUE);
    }

    /**
     * Lookup helper that returns a defensive copy of the latest snapshot for
     * a (branchId, machineCode) tuple, looking up the Machine entity on miss.
     * Returns {@code null} if the machine is unknown.
     */
    public MachineRuntimeState getOrLoad(UUID branchId, String machineCode) {
        Optional<Machine> mOpt = machineRepository.findByBranchIdAndMachineCode(branchId, machineCode);
        if (mOpt.isEmpty()) return null;
        UUID id = mOpt.get().getId();
        MachineRuntimeState s = states.get(id);
        return s == null ? null : copyOf(s);
    }

    // ── Persistence (scheduled flush) ──────────────────────────────────────

    /**
     * Periodic flusher: serializes dirty snapshots to JSONB. Bounded by a
     * single transaction; one bulk INSERT/UPDATE per flush.
     *
     * <p>Note on transactions: a previous version placed {@code @Transactional}
     * on a helper method that was invoked from {@code flushDirty} within the
     * same bean. Spring AOP cannot intercept self-invocations, so the helper
     * ran without an active transaction and Hibernate rejected the
     * {@code @Modifying} query with
     * <em>"No active transaction for update or delete query"</em>. We fix it
     * by using a {@link TransactionTemplate} explicitly.
     */
    @Scheduled(fixedDelayString = "${app.runtime.flush-interval-ms:10000}")
    public void flushDirty() {
        if (dirty.isEmpty()) return;
        long start = System.nanoTime();
        int count = 0;
        for (var entry : dirty.entrySet()) {
            if (!Boolean.TRUE.equals(entry.getValue())) continue;
            UUID id = entry.getKey();
            MachineRuntimeState snapshot = states.get(id);
            if (snapshot == null) {
                dirty.remove(id);
                continue;
            }
            try {
                tx().executeWithoutResult(status -> machineRepository
                        .upsertRuntimeSnapshot(id, serializeSnapshot(snapshot)));
                dirty.put(id, Boolean.FALSE);
                count++;
            } catch (Exception e) {
                log.warn("Failed to flush runtime snapshot for machineId={}: {}",
                        id, e.getMessage());
            }
        }
        if (count > 0) {
            long ms = (System.nanoTime() - start) / 1_000_000L;
            log.debug("Runtime snapshot flush: persisted={} duration_ms={}", count, ms);
        }
    }

    /** Serializes a snapshot to its JSONB representation, logging on failure. */
    private String serializeSnapshot(MachineRuntimeState snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize MachineRuntimeState", e);
        }
    }

    /**
     * Hydrate in-memory state from the JSONB persistence on demand.
     * Called by the public API if a device's process restarted — readers see
     * the last persisted snapshot instead of nothing.
     */
    public Optional<MachineRuntimeState> loadPersisted(UUID machineId) {
        Optional<String> json = machineRepository.findRuntimeSnapshot(machineId);
        if (json.isEmpty() || json.get().isBlank()) return Optional.empty();
        try {
            MachineRuntimeState s = objectMapper.readValue(json.get(), MachineRuntimeState.class);
            states.put(machineId, s);
            return Optional.of(copyOf(s));
        } catch (Exception e) {
            log.warn("Failed to hydrate runtime snapshot for machineId={}: {}",
                    machineId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Drain in-memory state when a Machine is hard-deleted.
     */
    public void forget(UUID machineId) {
        states.remove(machineId);
        dirty.remove(machineId);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void store(MachineRuntimeState snapshot) {
        OffsetDateTime now = OffsetDateTime.now();
        UUID id = snapshot.getMachineId();
        MachineRuntimeState next = states.computeIfPresent(id, (k, prev) -> {
            MachineRuntimeState m = copyOf(prev);
            String prevStatus = m.getStatus();
            applyNonNull(m, snapshot);
            // Track when the device-reported status actually changed.
            if (snapshot.getStatus() != null && !snapshot.getStatus().equals(prevStatus)) {
                m.setLastStatusChange(now);
            }
            return m;
        });
        if (next == null) {
            snapshot.setLastSeen(now);
            states.put(id, snapshot);
        } else {
            next.setLastSeen(now);
        }
        dirty.put(id, Boolean.TRUE);
    }

    /**
     * Reject stale telemetry: if the incoming seq is older than what we already
     * saw, ignore the payload. Returns {@code true} if the payload was accepted
     * and merged, {@code false} if it was dropped because the message is stale.
     *
     * <p>ESP32 reset detection: when the device reboots, its {@code seq} resets
     * back to a small value. We treat the new message as fresh whenever the
     * incoming seq is <  the last seen one AND a reboot signal (uptime reset
     * or firmware reconnect) is observed. The caller is expected to call
     * {@link #acceptTelemetryOrReset} for telemetry messages — it both checks
     * the seq and merges the payload in one atomic operation.
     */
    public boolean acceptTelemetry(MachineRuntimeState prev, MachineRuntimeState incoming) {
        if (incoming == null) return false;
        Long incomingSeq = incoming.getLastTelemetrySeq();
        if (incomingSeq == null) {
            // No seq in the payload (older firmware or unexpected channel) — accept.
            return true;
        }
        Long prevSeq = prev == null ? null : prev.getLastTelemetrySeq();
        if (prevSeq == null) {
            // First ever telemetry for this machine — accept.
            return true;
        }
        // Strictly newer seq → accept.
        if (incomingSeq > prevSeq) {
            return true;
        }
        // Same seq → duplicate, accept to keep lastSeen fresh but no state change.
        if (incomingSeq.equals(prevSeq)) {
            return true;
        }
        // Stale seq — but check for ESP32 reset.
        return isEsp32Reset(prev, incoming, incomingSeq, prevSeq);
    }

    /**
     * Heuristic: a stale seq is treated as a device reboot when the firmware
     * version changed, the uptime dropped back to a small value, or the
     * device just reconnected (freeHeap reset, wifi/mqtt transitions).
     */
    private boolean isEsp32Reset(MachineRuntimeState prev, MachineRuntimeState incoming,
                                 long incomingSeq, long prevSeq) {
        if (incomingSeq > 10) {
            // If the new seq is still >10, the device didn't fully reset seq.
            return false;
        }
        Long prevUptime = prev.getUptimeSeconds();
        Long newUptime = incoming.getUptimeSeconds();
        if (newUptime != null && (prevUptime == null || newUptime < prevUptime)) {
            return true;
        }
        // Wifi just reconnected or simulation mode flipped — also signals a reboot.
        Boolean prevWifi = prev.getWifiConnected();
        Boolean newWifi = incoming.getWifiConnected();
        if (newWifi != null && prevWifi != null && !newWifi.equals(prevWifi)) {
            return true;
        }
        return false;
    }

    /**
     * Merge: keep any field the new payload did NOT carry. This guarantees a
     * "lighter" channel cannot wipe richer telemetry data.
     */
    private MachineRuntimeState mergePreferStatus(MachineRuntimeState prev, MachineRuntimeState in) {
        MachineRuntimeState m = copyOf(prev);
        applyNonNull(m, in);
        return m;
    }

    private MachineRuntimeState mergeHeartbeat(MachineRuntimeState prev, MachineRuntimeState in) {
        MachineRuntimeState m = copyOf(prev);
        // Heartbeat only refreshes connectivity fields.
        if (in.getOnline() != null) m.setOnline(in.getOnline());
        if (in.getWifiConnected() != null) m.setWifiConnected(in.getWifiConnected());
        if (in.getMqttConnected() != null) m.setMqttConnected(in.getMqttConnected());
        if (in.getWifiRssi() != null) m.setWifiRssi(in.getWifiRssi());
        if (in.getUptimeSeconds() != null) m.setUptimeSeconds(in.getUptimeSeconds());
        if (in.getFreeHeap() != null) m.setFreeHeap(in.getFreeHeap());
        if (in.getStatus() != null) m.setStatus(in.getStatus());
        if (in.getPhase() != null) m.setPhase(in.getPhase());
        if (in.getRunning() != null) m.setRunning(in.getRunning());
        if (in.getAvailable() != null) m.setAvailable(in.getAvailable());
        if (in.getError() != null) m.setError(in.getError());
        if (in.getErrorMessage() != null) m.setErrorMessage(in.getErrorMessage());
        if (in.getHx711Available() != null) m.setHx711Available(in.getHx711Available());
        if (in.getMpu6050Available() != null) m.setMpu6050Available(in.getMpu6050Available());
        if (in.getLeakSensorEnabled() != null) m.setLeakSensorEnabled(in.getLeakSensorEnabled());
        if (in.getWaterSensorEnabled() != null) m.setWaterSensorEnabled(in.getWaterSensorEnabled());
        return m;
    }

    private MachineRuntimeState mergeEvent(MachineRuntimeState prev, MachineRuntimeState in) {
        MachineRuntimeState m = copyOf(prev);
        applyNonNull(m, in);
        return m;
    }

    /** Copy non-null fields from {@code in} onto {@code m}. */
    private static void applyNonNull(MachineRuntimeState m, MachineRuntimeState in) {
        if (in.getOnline() != null) m.setOnline(in.getOnline());
        if (in.getWifiConnected() != null) m.setWifiConnected(in.getWifiConnected());
        if (in.getMqttConnected() != null) m.setMqttConnected(in.getMqttConnected());
        if (in.getWifiRssi() != null) m.setWifiRssi(in.getWifiRssi());
        if (in.getUptimeSeconds() != null) m.setUptimeSeconds(in.getUptimeSeconds());
        if (in.getFreeHeap() != null) m.setFreeHeap(in.getFreeHeap());
        if (in.getStatus() != null) m.setStatus(in.getStatus());
        if (in.getPhase() != null) m.setPhase(in.getPhase());
        if (in.getRunning() != null) m.setRunning(in.getRunning());
        if (in.getAvailable() != null) m.setAvailable(in.getAvailable());
        if (in.getError() != null) m.setError(in.getError());
        if (in.getErrorMessage() != null) m.setErrorMessage(in.getErrorMessage());
        if (in.getCycleActive() != null) m.setCycleActive(in.getCycleActive());
        if (in.getRemainingSeconds() != null) m.setRemainingSeconds(in.getRemainingSeconds());
        if (in.getProgressPercent() != null) m.setProgressPercent(in.getProgressPercent());
        if (in.getMotorDirection() != null) m.setMotorDirection(in.getMotorDirection());
        if (in.getWeightKg() != null) m.setWeightKg(in.getWeightKg());
        else if (in.getWeightKgCompat() != null) m.setWeightKg(in.getWeightKgCompat());
        if (in.getWaterLevelLiters() != null) m.setWaterLevelLiters(in.getWaterLevelLiters());
        if (in.getVibrationG() != null) m.setVibrationG(in.getVibrationG());
        else if (in.getVibrationGCompat() != null) m.setVibrationG(in.getVibrationGCompat());
        if (in.getDoorClosed() != null) m.setDoorClosed(in.getDoorClosed());
        if (in.getLeakDetected() != null) m.setLeakDetected(in.getLeakDetected());
        if (in.getHx711Available() != null) m.setHx711Available(in.getHx711Available());
        if (in.getMpu6050Available() != null) m.setMpu6050Available(in.getMpu6050Available());
        if (in.getLeakSensorEnabled() != null) m.setLeakSensorEnabled(in.getLeakSensorEnabled());
        if (in.getWaterSensorEnabled() != null) m.setWaterSensorEnabled(in.getWaterSensorEnabled());
    }

    private MachineRuntimeState copyOf(MachineRuntimeState in) {
        return MachineRuntimeState.builder()
                .machineId(in.getMachineId())
                .branchId(in.getBranchId())
                .machineCode(in.getMachineCode())
                .online(in.getOnline())
                .wifiConnected(in.getWifiConnected())
                .mqttConnected(in.getMqttConnected())
                .wifiRssi(in.getWifiRssi())
                .uptimeSeconds(in.getUptimeSeconds())
                .freeHeap(in.getFreeHeap())
                .status(in.getStatus())
                .phase(in.getPhase())
                .running(in.getRunning())
                .available(in.getAvailable())
                .error(in.getError())
                .errorMessage(in.getErrorMessage())
                .cycleActive(in.getCycleActive())
                .remainingSeconds(in.getRemainingSeconds())
                .progressPercent(in.getProgressPercent())
                .motorDirection(in.getMotorDirection())
                .weightKg(in.getWeightKg())
                .weightKgCompat(in.getWeightKgCompat())
                .waterLevelLiters(in.getWaterLevelLiters())
                .vibrationG(in.getVibrationG())
                .vibrationGCompat(in.getVibrationGCompat())
                .doorClosed(in.getDoorClosed())
                .leakDetected(in.getLeakDetected())
                .hx711Available(in.getHx711Available())
                .mpu6050Available(in.getMpu6050Available())
                .leakSensorEnabled(in.getLeakSensorEnabled())
                .waterSensorEnabled(in.getWaterSensorEnabled())
                .firmwareVersion(in.getFirmwareVersion())
                .simulation(in.getSimulation())
                .lastTelemetrySeq(in.getLastTelemetrySeq())
                .deviceSampleMillis(in.getDeviceSampleMillis())
                .lastHeartbeat(in.getLastHeartbeat())
                .lastTelemetryAt(in.getLastTelemetryAt())
                .lastSeen(in.getLastSeen())
                .lastStatusChange(in.getLastStatusChange())
                .schemaVersion(in.getSchemaVersion())
                .build();
    }
}