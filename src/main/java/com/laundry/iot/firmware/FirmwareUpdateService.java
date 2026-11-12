package com.laundry.iot.firmware;

import com.laundry.devicelink.DeviceLink;
import com.laundry.devicelink.SerialPortControl;
import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.firmware.FirmwareUpdateStatus.State;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flashes a board from the web (Admin, page "Thiết bị IoT"). The backend is the program
 * holding the board's COM port, so it does the flashing itself:
 * <ol>
 *   <li>check the image ({@link FirmwareImage}) matches the board's type, the board is plugged in
 *       and its machine has no customer session;</li>
 *   <li>release the port, run esptool, take the port back;</li>
 *   <li>wait for the board's HELLO with the new version.</li>
 * </ol>
 * One board at a time. Progress lives in memory; the page polls {@link #status}.
 */
@Service
@Slf4j
public class FirmwareUpdateService {

    /** A customer is using the machine: flashing would kill the cycle. */
    private static final Set<MachineStatus> BUSY = EnumSet.of(
            MachineStatus.RESERVED, MachineStatus.WAITING_START, MachineStatus.RUNNING, MachineStatus.PAUSED);
    private static final Pattern PROGRESS = Pattern.compile("\\((\\d{1,3}) ?%\\)");
    private static final int LOG_LINES = 30;

    private final IotDeviceRepository deviceRepository;
    private final MachineRepository machineRepository;
    private final MachineStartAuthorizationRepository startAuthorizationRepository;
    private final DeviceLink deviceLink;
    private final ObjectProvider<SerialPortControl> portControl;
    private final FirmwareFlasher flasher;
    private final FirmwareProperties properties;

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicReference<String> flashing = new AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "firmware-flash");
        t.setDaemon(true);
        return t;
    });

    public FirmwareUpdateService(IotDeviceRepository deviceRepository, MachineRepository machineRepository,
                                 MachineStartAuthorizationRepository startAuthorizationRepository,
                                 DeviceLink deviceLink, ObjectProvider<SerialPortControl> portControl,
                                 FirmwareFlasher flasher, FirmwareProperties properties) {
        this.deviceRepository = deviceRepository;
        this.machineRepository = machineRepository;
        this.startAuthorizationRepository = startAuthorizationRepository;
        this.deviceLink = deviceLink;
        this.portControl = portControl;
        this.flasher = flasher;
        this.properties = properties;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

    /** Checks everything, then starts flashing in the background and returns right away. */
    public FirmwareUpdateStatus start(String rawDeviceId, byte[] bytes, UUID adminId) {
        String deviceId = rawDeviceId.trim().toUpperCase(Locale.ROOT);
        FirmwareImage image;
        try {
            image = FirmwareImage.parse(bytes);
        } catch (FirmwareImage.InvalidFirmwareException e) {
            throw new ProvisioningRejectedException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        IotDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ProvisioningRejectedException(HttpStatus.NOT_FOUND, "Unknown device " + deviceId));
        if (device.getDeviceType() != null && device.getDeviceType() != image.deviceType()) {
            throw new ProvisioningRejectedException(HttpStatus.BAD_REQUEST, "This image is " + image.deviceType()
                    + " firmware; " + deviceId + " is a " + device.getDeviceType() + " board");
        }
        SerialPortControl ports = portControl.getIfAvailable();
        if (ports == null) {
            throw new ProvisioningRejectedException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The USB serial link is disabled (SERIAL_ENABLED=false)");
        }
        String port = deviceLink.portOf(deviceId);
        if (port == null) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    deviceId + " is not plugged into the store server");
        }
        requireIdle(device);
        if (!flashing.compareAndSet(null, deviceId)) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT,
                    "Already flashing " + flashing.get() + "; wait for it to finish");
        }
        Job job = new Job(deviceId, port, device.getFirmwareVersion(), image.version());
        try {
            Path file = Files.createTempFile("laundryos-" + deviceId + "-", ".bin");
            Files.write(file, image.bytes());
            jobs.put(deviceId, job);
            worker.execute(() -> run(job, file, ports));
        } catch (Exception e) {
            flashing.set(null);
            throw new ProvisioningRejectedException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Cannot start flashing: " + e.getMessage());
        }
        log.info("[FIRMWARE] {} on {}: {} -> {} (admin {})", deviceId, port, job.fromVersion, job.toVersion, adminId);
        return job.snapshot();
    }

    /** The last update of this board since the backend started. */
    public Optional<FirmwareUpdateStatus> status(String deviceId) {
        return Optional.ofNullable(jobs.get(deviceId.trim().toUpperCase(Locale.ROOT))).map(Job::snapshot);
    }

    private void requireIdle(IotDevice device) {
        if (device.getMachineId() == null) return;
        Machine machine = machineRepository.findById(device.getMachineId()).orElse(null);
        if (machine == null) return;
        if (BUSY.contains(machine.getStatus())) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "Machine " + machine.getMachineCode()
                    + " is " + machine.getStatus() + "; wait for the customer session to end");
        }
        if (startAuthorizationRepository.findActiveByMachine(machine.getId()).isPresent()) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "Machine " + machine.getMachineCode()
                    + " has a pending start authorization");
        }
    }

    private void run(Job job, Path file, SerialPortControl ports) {
        try {
            try {
                ports.release(job.port);
                Thread.sleep(properties.getReleaseDelayMs());
                int exit = flasher.flash(job.port, file, job::output);
                if (exit != 0) {
                    job.fail("esptool failed (exit " + exit + "): " + job.lastLine());
                } else {
                    job.rebooting();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.fail("Interrupted");
            } catch (Exception e) {
                job.fail(e.getMessage());
            } finally {
                ports.reclaim(job.port);
                try {
                    Files.deleteIfExists(file);
                } catch (Exception ignored) {
                    // temp file
                }
            }
            if (job.state == State.REBOOTING) awaitNewVersion(job);
        } finally {
            flashing.set(null);
            if (job.state == State.FAILED) log.warn("[FIRMWARE] {} failed: {}", job.deviceId, job.message);
            else log.info("[FIRMWARE] {} {}: {}", job.deviceId, job.state, job.message);
        }
    }

    /** The board boots the new image and says HELLO; the registry stores its version. */
    private void awaitNewVersion(Job job) {
        long deadline = System.currentTimeMillis() + properties.getRebootTimeoutSeconds() * 1000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                String version = deviceRepository.findByDeviceId(job.deviceId)
                        .map(IotDevice::getFirmwareVersion).orElse(null);
                if (deviceLink.isConnected(job.deviceId) && Objects.equals(version, job.toVersion)) {
                    job.done();
                    return;
                }
                Thread.sleep(500);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        job.fail("Flashed, but the board did not come back with " + job.toVersion + " within "
                + properties.getRebootTimeoutSeconds() + " s; check the cable and its serial log");
    }

    /** Mutable progress of one update; read through {@link #snapshot}. */
    private static final class Job {
        final String deviceId, port, fromVersion, toVersion;
        final OffsetDateTime startedAt = OffsetDateTime.now();
        private final ArrayDeque<String> log = new ArrayDeque<>();
        volatile State state = State.FLASHING;
        volatile int progress;
        volatile String message = "Writing the firmware";
        volatile OffsetDateTime finishedAt;

        Job(String deviceId, String port, String fromVersion, String toVersion) {
            this.deviceId = deviceId;
            this.port = port;
            this.fromVersion = fromVersion;
            this.toVersion = toVersion;
        }

        synchronized void output(String line) {
            if (log.size() == LOG_LINES) log.removeFirst();
            log.addLast(line);
            Matcher m = PROGRESS.matcher(line);
            if (m.find()) progress = Math.min(100, Integer.parseInt(m.group(1)));
        }

        synchronized String lastLine() {
            return log.isEmpty() ? "no output" : log.getLast();
        }

        void rebooting() {
            progress = 100;
            message = "Written and verified; waiting for the board to restart";
            state = State.REBOOTING;
        }

        void done() {
            message = "Running " + toVersion;
            finishedAt = OffsetDateTime.now();
            state = State.DONE;
        }

        void fail(String reason) {
            message = reason == null ? "Flashing failed" : reason;
            finishedAt = OffsetDateTime.now();
            state = State.FAILED;
        }

        synchronized FirmwareUpdateStatus snapshot() {
            return new FirmwareUpdateStatus(deviceId, port, state, progress, fromVersion, toVersion, message,
                    List.copyOf(log), startedAt, finishedAt);
        }
    }
}
