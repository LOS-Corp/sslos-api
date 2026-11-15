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

/**
 * Transport-independent core of the device link: tracks which board is on which MQTT session,
 * authenticates its HELLO, answers with the board's {@link LinkState}, routes the frames of bound
 * boards to the handlers and sends commands back.
 *
 * <p>The MQTT transport feeds it {@link #connected}, {@link #frameReceived} and {@link #disconnected};
 * tests drive it with fake connections.
 *
 * <p>A HELLO is only accepted after the board signed a fresh nonce with the key it presented
 * ({@code challenge} → {@code proof}); the registry then checks that key against the one pinned for the board.
 * Boards get their own broker account over HTTP ({@code POST /api/devices/register}); a revoked board's account
 * is removed here ({@link DeviceCredentials}).
 *
 * <p>Registry and router are looked up lazily: they reach services that send commands through this link.
 */
@Component
@Slf4j
public class DeviceLinkHub implements DeviceLink {

    private final ObjectProvider<DeviceRegistryService> registry;
    private final ObjectProvider<DeviceMessageRouter> router;
    private final ObjectProvider<DeviceCredentials> credentials;
    private final ObjectMapper objectMapper;

    private final Map<DeviceConnection, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, DeviceConnection> byDevice = new ConcurrentHashMap<>();
    /** Boards refused at authentication, so trusting their new key can wake them right away. */
    private final Map<String, DeviceConnection> refused = new ConcurrentHashMap<>();

    public DeviceLinkHub(ObjectProvider<DeviceRegistryService> registry,
                         ObjectProvider<DeviceMessageRouter> router,
                         ObjectProvider<DeviceCredentials> credentials,
                         ObjectMapper objectMapper) {
        this.registry = registry;
        this.router = router;
        this.credentials = credentials;
        this.objectMapper = objectMapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hello(String deviceId, String firmwareVersion, String deviceType,
                 String publicKey, String ip, Integer rssi) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Proof(String nonce, String signature) {
    }

    /** A board that failed authentication says HELLO again at most this often (no DB work in between). */
    static final long AUTH_RETRY_MS = 20_000;

    /** Per-session state; {@code link} caches the binding so frames are routed without a DB read. */
    private static final class Session {
        volatile String deviceId;
        volatile LinkState link;
        /** HELLO waiting for its {@code proof}, and the nonce it must sign. */
        volatile Hello pendingHello;
        volatile byte[] pendingKey;
        volatile String nonce;
        volatile long authFailedAt;
    }

    // ── Events from the transport ──────────────────────────────────────────

    public void connected(DeviceConnection connection) {
        sessions.put(connection, new Session());
        log.info("[LINK] {} online on the broker", connection.deviceId());
        write(connection, LinkState.of(LinkState.State.HELLO_REQUIRED, null));
    }

    public void disconnected(DeviceConnection connection) {
        Session session = sessions.remove(connection);
        log.info("[LINK] {} left the broker", connection.deviceId());
        refused.values().remove(connection);
        if (session != null && session.deviceId != null) release(session.deviceId, connection);
    }

    /** One message the board published on {@code up/<channel>}. */
    public void frameReceived(DeviceConnection connection, String channel, String payload) {
        Session session = sessions.get(connection);
        if (session == null) return;
        if (!DeviceFrame.isJsonObject(payload)) {
            log.debug("[LINK] {} sent a non-JSON payload on '{}'; dropped", connection.deviceId(), channel);
            return;
        }
        if (DeviceFrame.HELLO.equals(channel)) {
            if (session.deviceId != null) {
                // Already authenticated: answer with the current state only, never reopen the handshake.
                refresh(connection);
                return;
            }
            onHello(connection, session, payload);
            return;
        }
        if (DeviceFrame.PROOF.equals(channel)) {
            onProof(connection, session, payload);
            return;
        }
        if (session.deviceId == null) {
            if (session.authFailedAt != 0 || session.pendingHello != null) return;  // refused or mid-handshake
            write(connection, LinkState.of(LinkState.State.HELLO_REQUIRED, null));
            return;
        }
        Optional<DeviceChannel> deviceChannel = DeviceChannel.fromPath(channel);
        if (deviceChannel.isEmpty()) {
            log.warn("[LINK] {} sent unknown channel '{}'", session.deviceId, channel);
            return;
        }
        LinkState link = session.link;
        if (link == null || !link.isBound()) {
            log.debug("[LINK] {} is not bound to a machine; dropping {}", session.deviceId, channel);
            return;
        }
        router.getObject().route(link.machineCode(), deviceChannel.get(), payload);
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
                    + " is not connected");
        }
        connection.send(DeviceFrame.COMMAND, json);
        log.info("[LINK] TX {} -> {}", machine.getMachineCode(), deviceId);
        log.debug("[LINK] TX payload {}", json);
    }

    @Override
    public boolean isConnected(String deviceId) {
        return deviceId != null && byDevice.containsKey(deviceId);
    }

    @Override
    public void sendFrame(String deviceId, String channel, String json) {
        DeviceConnection connection = deviceId == null ? null : byDevice.get(deviceId);
        if (connection == null) throw new DeviceNotConnectedException("Device " + deviceId + " is not connected");
        connection.send(channel, json);
        log.info("[LINK] TX {} -> {}", channel, deviceId);
    }

    // ── Pushing the link state ─────────────────────────────────────────────

    /** Approval or binding changed: tell the board right away (after the change committed). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLinkChanged(DeviceLinkChanged event) {
        DeviceConnection connection = byDevice.get(event.deviceId());
        if (connection != null) refresh(connection);
        revokeAccountIfRevoked(event.deviceId());
        DeviceConnection waiting = refused.remove(event.deviceId());
        if (waiting != null) {
            // e.g. an admin trusted the board's new key: let it retry now instead of after AUTH_RETRY_MS.
            Session session = sessions.get(waiting);
            if (session != null) session.authFailedAt = 0;
            write(waiting, LinkState.of(LinkState.State.HELLO_REQUIRED, event.deviceId()));
        }
    }

    /** Keepalive: the board treats the link as lost when no frame arrived for 30 s. */
    @Scheduled(fixedDelayString = "${app.mqtt.keepalive-ms:10000}")
    public void keepalive() {
        byDevice.values().forEach(this::refresh);
    }

    // ── Internals ──────────────────────────────────────────────────────────

    private void onHello(DeviceConnection connection, Session session, String payload) {
        Hello hello;
        try {
            hello = objectMapper.readValue(payload, Hello.class);
        } catch (JsonProcessingException e) {
            log.warn("[LINK] {} sent an unreadable HELLO", connection.deviceId());
            return;
        }
        String deviceId = DeviceIds.normalize(hello.deviceId());
        if (!DeviceIds.isValid(deviceId)) {
            log.warn("[LINK] {} sent HELLO with invalid deviceId '{}'", connection.deviceId(), hello.deviceId());
            return;
        }
        if (!connection.deviceId().equals(deviceId)) {
            // The broker ACL bound this session to another client id: a board pretending to be someone else.
            log.warn("[LINK] {} sent HELLO as {}; refused", connection.deviceId(), deviceId);
            write(connection, LinkState.of(LinkState.State.AUTH_FAILED, connection.deviceId()));
            return;
        }
        if (session.authFailedAt != 0 && System.currentTimeMillis() - session.authFailedAt < AUTH_RETRY_MS) return;
        Optional<byte[]> key = DeviceKeys.decodePublicKey(hello.publicKey());
        if (key.isEmpty()) {
            log.warn("[LINK] {} sent HELLO without a valid public key", deviceId);
            session.authFailedAt = System.currentTimeMillis();
            write(connection, LinkState.of(LinkState.State.AUTH_FAILED, deviceId));
            return;
        }
        // Prove possession of the key first; nothing touches the database before that.
        session.pendingHello = hello;
        session.pendingKey = key.get();
        session.nonce = DeviceKeys.newNonce();
        writeJson(connection, DeviceFrame.CHALLENGE, Map.of("nonce", session.nonce));
    }

    private void onProof(DeviceConnection connection, Session session, String payload) {
        Hello hello = session.pendingHello;
        byte[] key = session.pendingKey;
        String nonce = session.nonce;
        if (hello == null || key == null || nonce == null) return;
        Proof proof;
        try {
            proof = objectMapper.readValue(payload, Proof.class);
        } catch (JsonProcessingException e) {
            return;
        }
        // An answer to an earlier challenge (the board said HELLO twice): wait for the current one.
        if (!nonce.equals(proof.nonce())) return;
        session.pendingHello = null;
        session.pendingKey = null;
        session.nonce = null;
        String deviceId = DeviceIds.normalize(hello.deviceId());
        if (!DeviceKeys.verify(key, DeviceKeys.challengeMessage(deviceId, nonce), proof.signature())) {
            log.warn("[LINK] {} failed the signature check", deviceId);
            session.authFailedAt = System.currentTimeMillis();
            write(connection, LinkState.of(LinkState.State.AUTH_FAILED, deviceId));
            return;
        }
        accept(connection, session, deviceId, hello, new DeviceIdentity(key, hello.ip(), hello.rssi()));
    }

    /** The HELLO carries a valid signature: bind the session and answer with the state. */
    private void accept(DeviceConnection connection, Session session, String deviceId, Hello hello,
                        DeviceIdentity identity) {
        DeviceConnection other = byDevice.get(deviceId);
        if (other != null && other != connection && sessions.containsKey(other)) {
            log.warn("[LINK] {} already has a live session; ignoring the new one", deviceId);
            write(connection, LinkState.of(LinkState.State.DUPLICATE, deviceId));
            return;
        }
        LinkState link = registry.getObject().onHello(deviceId, hello.firmwareVersion(), hello.deviceType(), identity);
        if (link.state() == LinkState.State.AUTH_FAILED) {
            // The key is not the one pinned for this board: keep the session unbound, nothing is routed.
            log.warn("[LINK] {} presented a key that is not trusted", deviceId);
            if (session.deviceId != null) {
                // This session was bound by an earlier, trusted HELLO: whoever speaks now is not that board.
                release(session.deviceId, connection);
                session.deviceId = null;
                session.link = null;
            }
            session.authFailedAt = System.currentTimeMillis();
            refused.put(deviceId, connection);
            write(connection, link);
            return;
        }
        session.authFailedAt = 0;
        refused.remove(deviceId, connection);
        session.deviceId = deviceId;
        byDevice.put(deviceId, connection);
        session.link = link;
        log.info("[LINK] {} is {} (fw {})", deviceId, link.state(), hello.firmwareVersion());
        write(connection, link);
    }

    /** A revoked board loses its broker account too, so the broker itself refuses it. */
    private void revokeAccountIfRevoked(String deviceId) {
        DeviceCredentials accounts = credentials.getIfAvailable();
        if (accounts == null) return;
        try {
            if (registry.getObject().linkState(deviceId).state() == LinkState.State.REVOKED) accounts.revoke(deviceId);
        } catch (RuntimeException e) {
            log.warn("[LINK] cannot check whether {} was revoked: {}", deviceId, e.getMessage());
        }
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
        writeJson(connection, DeviceFrame.LINK, link);
    }

    private void writeJson(DeviceConnection connection, String channel, Object body) {
        try {
            writeRaw(connection, channel, objectMapper.writeValueAsString(body));
        } catch (JsonProcessingException e) {
            log.warn("[LINK] cannot encode '{}' for {}: {}", channel, connection.deviceId(), e.getMessage());
        }
    }

    private void writeRaw(DeviceConnection connection, String channel, String json) {
        try {
            connection.send(channel, json);
        } catch (RuntimeException e) {
            log.warn("[LINK] cannot publish to {}: {}", connection.deviceId(), e.getMessage());
        }
    }
}
