package com.laundry.devicelink;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.iot.service.DeviceRegistryService;
import com.laundry.machine.entity.Machine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Transport-independent core of the device link: tracks which board is on which
 * connection, answers HELLO with the board's {@link LinkState}, routes the
 * frames of bound boards to the handlers and sends commands back.
 *
 * <p>The serial port scanner feeds it {@link #connected}, {@link #lineReceived}
 * and {@link #disconnected}; tests can drive it with fake connections.
 *
 * <p>Registry and router are looked up lazily: they reach services that send
 * commands through this link.
 */
@Component
@Slf4j
public class DeviceLinkHub implements DeviceLink {

    private static final Pattern DEVICE_ID = Pattern.compile("^ESP-[0-9A-F]{12}$");

    private final ObjectProvider<DeviceRegistryService> registry;
    private final ObjectProvider<DeviceMessageRouter> router;
    private final ObjectMapper objectMapper;

    private final Map<DeviceConnection, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, DeviceConnection> byDevice = new ConcurrentHashMap<>();

    public DeviceLinkHub(ObjectProvider<DeviceRegistryService> registry,
                         ObjectProvider<DeviceMessageRouter> router,
                         ObjectMapper objectMapper) {
        this.registry = registry;
        this.router = router;
        this.objectMapper = objectMapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hello(String deviceId, String firmwareVersion, String deviceType) {
    }

    /** Per-connection state; {@code link} caches the binding so frames are routed without a DB read. */
    private static final class Session {
        volatile String deviceId;
        volatile LinkState link;
    }

    // ── Events from the transport ──────────────────────────────────────────

    public void connected(DeviceConnection connection) {
        sessions.put(connection, new Session());
        log.info("[LINK] {} opened", connection.name());
        write(connection, LinkState.of(LinkState.State.HELLO_REQUIRED, null));
    }

    public void disconnected(DeviceConnection connection) {
        Session session = sessions.remove(connection);
        log.info("[LINK] {} closed", connection.name());
        if (session != null && session.deviceId != null) release(session.deviceId, connection);
    }

    public void lineReceived(DeviceConnection connection, String line) {
        Session session = sessions.get(connection);
        if (session == null) return;
        Optional<DeviceFrame> frame = DeviceFrame.parse(line);
        if (frame.isEmpty()) {
            log.debug("[DEVICE {}] {}", label(connection, session), line);
            return;
        }
        String channel = frame.get().channel();
        if (DeviceFrame.HELLO.equals(channel)) {
            onHello(connection, session, frame.get().payload());
            return;
        }
        if (session.deviceId == null) {
            write(connection, LinkState.of(LinkState.State.HELLO_REQUIRED, null));
            return;
        }
        Optional<DeviceChannel> deviceChannel = DeviceChannel.fromPath(channel);
        if (deviceChannel.isEmpty()) {
            log.warn("[LINK] {} sent unknown channel '{}'", label(connection, session), channel);
            return;
        }
        LinkState link = session.link;
        if (link == null || !link.isBound()) {
            log.debug("[LINK] {} is not bound to a machine; dropping {}", session.deviceId, channel);
            return;
        }
        router.getObject().route(link.branchId(), link.machineCode(), deviceChannel.get(), frame.get().payload());
    }

    // ── DeviceLink ─────────────────────────────────────────────────────────

    @Override
    public void send(Machine machine, String json) {
        String deviceId = machine.getDeviceId();
        if (deviceId == null) {
            throw new DeviceNotConnectedException("Machine " + machine.getMachineCode() + " has no bound device");
        }
        DeviceConnection connection = byDevice.get(deviceId);
        if (connection == null) {
            throw new DeviceNotConnectedException("Device " + deviceId + " of " + machine.getMachineCode()
                    + " is not plugged in");
        }
        connection.writeLine(new DeviceFrame(DeviceFrame.COMMAND, json).format());
        log.info("[LINK] TX {} -> {} ({})", machine.getMachineCode(), deviceId, connection.name());
        log.debug("[LINK] TX payload {}", json);
    }

    @Override
    public boolean isConnected(String deviceId) {
        return deviceId != null && byDevice.containsKey(deviceId);
    }

    @Override
    public String portOf(String deviceId) {
        DeviceConnection connection = deviceId == null ? null : byDevice.get(deviceId);
        return connection == null ? null : connection.name();
    }

    // ── Pushing the link state ─────────────────────────────────────────────

    /** Approval or binding changed: tell the board right away (after the change committed). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLinkChanged(DeviceLinkChanged event) {
        DeviceConnection connection = byDevice.get(event.deviceId());
        if (connection != null) refresh(connection);
    }

    /** Keepalive: the board treats the link as lost when no frame arrived for a while. */
    @Scheduled(fixedDelayString = "${app.serial.keepalive-ms:10000}")
    public void keepalive() {
        byDevice.values().forEach(this::refresh);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private void onHello(DeviceConnection connection, Session session, String payload) {
        Hello hello;
        try {
            hello = objectMapper.readValue(payload, Hello.class);
        } catch (JsonProcessingException e) {
            log.warn("[LINK] {} sent an unreadable HELLO", connection.name());
            return;
        }
        String deviceId = hello.deviceId() == null ? "" : hello.deviceId().trim().toUpperCase();
        if (!DEVICE_ID.matcher(deviceId).matches()) {
            log.warn("[LINK] {} sent HELLO with invalid deviceId '{}'", connection.name(), hello.deviceId());
            return;
        }
        DeviceConnection other = byDevice.get(deviceId);
        if (other != null && other != connection && sessions.containsKey(other)) {
            log.warn("[LINK] {} is already connected on {}; ignoring it on {}", deviceId, other.name(), connection.name());
            write(connection, LinkState.of(LinkState.State.DUPLICATE, deviceId));
            return;
        }
        if (session.deviceId != null && !session.deviceId.equals(deviceId)) {
            // Another board on the same port without a close in between: forget the old one.
            release(session.deviceId, connection);
        }
        session.deviceId = deviceId;
        byDevice.put(deviceId, connection);
        LinkState link = registry.getObject().onHello(deviceId, hello.firmwareVersion(), hello.deviceType());
        session.link = link;
        log.info("[LINK] {} is {} on {} (fw {})", deviceId, link.state(), connection.name(), hello.firmwareVersion());
        write(connection, link);
    }

    private void refresh(DeviceConnection connection) {
        Session session = sessions.get(connection);
        if (session == null || session.deviceId == null) return;
        try {
            LinkState link = registry.getObject().linkState(session.deviceId);
            session.link = link;
            write(connection, link);
        } catch (RuntimeException e) {
            log.warn("[LINK] cannot refresh {}: {}", session.deviceId, e.getMessage());
        }
    }

    private void release(String deviceId, DeviceConnection connection) {
        if (byDevice.remove(deviceId, connection)) {
            try {
                registry.getObject().onDisconnected(deviceId);
            } catch (RuntimeException e) {
                log.warn("[LINK] cannot mark {} offline: {}", deviceId, e.getMessage());
            }
        }
    }

    private void write(DeviceConnection connection, LinkState link) {
        try {
            connection.writeLine(new DeviceFrame(DeviceFrame.LINK, objectMapper.writeValueAsString(link)).format());
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("[LINK] cannot write to {}: {}", connection.name(), e.getMessage());
        }
    }

    private static String label(DeviceConnection connection, Session session) {
        return session.deviceId == null ? connection.name() : session.deviceId + "@" + connection.name();
    }
}
