package com.laundry.devicelink.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.laundry.devicelink.DeviceConnection;
import com.laundry.devicelink.DeviceFrame;
import com.laundry.devicelink.DeviceLinkHub;
import com.laundry.devicelink.SerialPortControl;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Finds the boards' USB serial ports, opens each one and feeds its lines to the
 * {@link DeviceLinkHub}. Rescans periodically, so a board can be plugged in or
 * out while the backend runs.
 *
 * <p>Only one program can hold a port: close the Arduino/PlatformIO serial
 * monitor before starting the backend.
 */
@Component
@ConditionalOnProperty(prefix = "app.serial", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class SerialPortScanner implements SerialPortControl {

    private final DeviceLinkHub hub;
    private final SerialProperties properties;
    private final Pattern autoPattern;

    private final Map<String, PortConnection> open = new ConcurrentHashMap<>();
    /** Ports that failed to open; logged once, retried on every scan. */
    private final Set<String> failed = ConcurrentHashMap.newKeySet();
    /** Ports lent to another program (firmware flashing): not reopened until reclaimed. */
    private final Set<String> released = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService scheduler;
    private volatile boolean reportedNoPorts;
    private volatile boolean reportedNativeFailure;

    public SerialPortScanner(DeviceLinkHub hub, SerialProperties properties) {
        this.hub = hub;
        this.properties = properties;
        this.autoPattern = Pattern.compile(properties.getPortPattern());
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (scheduler != null) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "serial-scan");
            t.setDaemon(true);
            return t;
        });
        log.info("[SERIAL] Scanning {} every {} ms at {} baud",
                properties.getPorts().isEmpty() ? "USB serial ports" : properties.getPorts(),
                properties.getScanIntervalMs(), properties.getBaudRate());
        scheduler.scheduleWithFixedDelay(this::scan, 0, properties.getScanIntervalMs(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        open.values().forEach(PortConnection::close);
        open.clear();
    }

    @Override
    public synchronized void release(String port) {
        released.add(port);
        PortConnection connection = open.get(port);
        if (connection != null) connection.close();
        log.info("[SERIAL] Released {} to another program", port);
    }

    @Override
    public synchronized void reclaim(String port) {
        if (!released.remove(port)) return;
        log.info("[SERIAL] Reclaiming {}", port);
        if (scheduler != null) scheduler.execute(this::scan);
    }

    synchronized void scan() {  // serialized with release(): a scan must not reopen a port being lent out
        try {
            SerialPort[] ports = SerialPort.getCommPorts();
            Set<String> present = new HashSet<>();
            for (SerialPort port : ports) {
                String name = port.getSystemPortName();
                present.add(name);
                if (!open.containsKey(name) && !released.contains(name) && wanted(port)) tryOpen(port);
            }
            // Unplugged without a disconnect event.
            for (String name : Set.copyOf(open.keySet())) {
                if (!present.contains(name)) open.get(name).close();
            }
            failed.retainAll(present);
            if (ports.length == 0 && !reportedNoPorts) {
                reportedNoPorts = true;
                log.info("[SERIAL] No serial port found yet; plug a board in over USB");
            } else if (ports.length > 0) {
                reportedNoPorts = false;
            }
        } catch (RuntimeException e) {
            log.warn("[SERIAL] Scan failed: {}", e.getMessage());
        } catch (LinkageError e) {
            // The native jSerialComm library did not load: no port can ever be listed. Say so once,
            // loudly; an uncaught Error would silently stop this scheduled task instead.
            if (!reportedNativeFailure) {
                reportedNativeFailure = true;
                log.error("[SERIAL] Cannot load the serial port library; boards will not connect: {}", e.toString());
            }
        }
    }

    private boolean wanted(SerialPort port) {
        String name = port.getSystemPortName();
        if (!properties.getPorts().isEmpty()) {
            return properties.getPorts().stream().map(String::trim)
                    .anyMatch(p -> p.equalsIgnoreCase(name) || p.equalsIgnoreCase(port.getSystemPortPath()));
        }
        String text = name + " " + port.getPortDescription() + " " + port.getDescriptivePortName();
        return autoPattern.matcher(text).matches();
    }

    private void tryOpen(SerialPort port) {
        String name = port.getSystemPortName();
        port.setComPortParameters(properties.getBaudRate(), 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
        port.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
        if (!port.openPort()) {
            if (failed.add(name)) {
                log.warn("[SERIAL] Cannot open {} ({}): in use by another program (serial monitor?)",
                        name, port.getDescriptivePortName());
            }
            return;
        }
        failed.remove(name);
        // DTR/RTS are left as opened (both asserted): on ESP32 dev boards that resets the board once and
        // boots it normally. Clearing them made the Windows CP210x driver hand back repeated garbage.
        PortConnection connection = new PortConnection(port);
        open.put(name, connection);
        port.addDataListener(connection);
        log.info("[SERIAL] Opened {} ({})", name, port.getDescriptivePortName());
        hub.connected(connection);
    }

    /** One open port: buffers bytes into lines and serializes writes. */
    private final class PortConnection implements DeviceConnection, SerialPortDataListener {

        private final SerialPort port;
        private final String name;
        private final ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        private boolean overflow;
        private volatile boolean closed;

        PortConnection(SerialPort port) {
            this.port = port;
            this.name = port.getSystemPortName();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public synchronized void writeLine(String text) {
            if (closed) throw new IllegalStateException(name + " is closed");
            byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
            int written = port.writeBytes(bytes, bytes.length);
            if (written != bytes.length) {
                close();
                throw new IllegalStateException("Write to " + name + " failed (" + written + "/" + bytes.length + ")");
            }
        }

        @Override
        public int getListeningEvents() {
            return SerialPort.LISTENING_EVENT_DATA_RECEIVED | SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
        }

        @Override
        public void serialEvent(SerialPortEvent event) {
            if (event.getEventType() == SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) {
                close();
                return;
            }
            byte[] data = event.getReceivedData();
            if (data == null) return;
            for (byte b : data) {
                if (b == '\n') {
                    emit();
                } else if (b != '\r') {
                    if (line.size() < DeviceFrame.MAX_LINE) line.write(b);
                    else overflow = true;
                }
            }
        }

        private void emit() {
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (overflow) {
                overflow = false;
                log.warn("[SERIAL] {} sent a line longer than {} bytes; dropped", name, DeviceFrame.MAX_LINE);
                return;
            }
            if (text.isBlank()) return;
            try {
                hub.lineReceived(this, text);
            } catch (RuntimeException e) {
                log.error("[SERIAL] {} line handling failed", name, e);
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            open.remove(name, this);
            try {
                port.removeDataListener();
                port.closePort();
            } catch (RuntimeException e) {
                log.debug("[SERIAL] closing {}: {}", name, e.getMessage());
            }
            hub.disconnected(this);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
