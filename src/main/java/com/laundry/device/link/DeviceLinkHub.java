package com.laundry.device.link;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.laundry.device.DeviceInbound;
import com.laundry.device.DeviceRegistry;
import com.laundry.device.MqttAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lõi liên kết thiết bị, không phụ thuộc transport: phiên MQTT nào là bo nào, bắt tay HELLO -> challenge -> proof,
 * trả down/link, chuyển tin của bo đã gắn máy/tủ cho {@link DeviceInbound}, gửi lệnh xuống.
 *
 * <p>HELLO chỉ được nhận sau khi bo ký một nonce mới bằng khóa nó trình (LAUNDRYOS-AUTH|id|nonce); registry kiểm khóa
 * đó với khóa đã ghim. Tài khoản broker của bo cấp qua HTTP (POST /api/devices/register).
 */
@Component
@Slf4j
public class DeviceLinkHub {

    /** Bo xác thực hỏng gửi HELLO lại tối đa mỗi 20 s (không chạm DB ở giữa). */
    static final long AUTH_RETRY_MS = 20_000;

    private final ObjectProvider<DeviceRegistry> registry;
    private final ObjectProvider<DeviceInbound> inbound;
    private final ObjectProvider<MqttAccountService> accounts;
    private final ObjectMapper mapper;

    private final Map<DeviceConnection, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, DeviceConnection> byDevice = new ConcurrentHashMap<>();
    /** Bo bị từ chối khi xác thực: admin xử lý xong thì đánh thức ngay. */
    private final Map<String, DeviceConnection> refused = new ConcurrentHashMap<>();

    public DeviceLinkHub(ObjectProvider<DeviceRegistry> registry, ObjectProvider<DeviceInbound> inbound,
                         ObjectProvider<MqttAccountService> accounts, ObjectMapper mapper) {
        this.registry = registry;
        this.inbound = inbound;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hello(String deviceId, String firmwareVersion, String deviceType, String publicKey, String ip, Integer rssi) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Proof(String nonce, String signature) {
    }

    private static final class Session {
        volatile String deviceId;
        volatile LinkState link;
        volatile Hello pendingHello;
        volatile byte[] pendingKey;
        volatile String nonce;
        volatile long authFailedAt;
    }

    // ---- Sự kiện từ transport ----

    public void connected(DeviceConnection connection) {
        sessions.put(connection, new Session());
        log.info("[LINK] {} online on the broker", connection.deviceId());
        write(connection, LinkState.of(LinkState.State.HELLO_REQUIRED, null));
    }

    public void disconnected(DeviceConnection connection) {
        Session session = sessions.remove(connection);
        refused.values().remove(connection);
        log.info("[LINK] {} left the broker", connection.deviceId());
        if (session != null && session.deviceId != null) release(session.deviceId, connection);
    }

    public void frameReceived(DeviceConnection connection, String channel, String payload) {
        Session session = sessions.get(connection);
        if (session == null) return;
        if (!DeviceFrame.isJsonObject(payload)) return;
        if (DeviceFrame.HELLO.equals(channel)) {
            if (session.deviceId != null) {
                refresh(connection);   // đã xác thực: chỉ trả trạng thái, không mở lại bắt tay
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
            if (session.authFailedAt != 0 || session.pendingHello != null) return;
            write(connection, LinkState.of(LinkState.State.HELLO_REQUIRED, null));
            return;
        }
        if (!DeviceFrame.DEVICE_CHANNELS.contains(channel)) {
            log.warn("[LINK] {} sent unknown channel '{}'", session.deviceId, channel);
            return;
        }
        LinkState link = session.link;
        if (link == null || !link.isBound()) return;   // chưa gắn máy/tủ: bỏ qua
        inbound.getObject().route(session.deviceId, channel, payload);
    }

    // ---- Gửi xuống ----

    public boolean isConnected(String deviceId) {
        return deviceId != null && byDevice.containsKey(deviceId);
    }

    /** Gửi lên down/{channel} của bo; ném {@link DeviceNotConnectedException} khi bo không online. */
    public void send(String deviceId, String channel, String json) {
        DeviceConnection connection = deviceId == null ? null : byDevice.get(deviceId);
        if (connection == null) throw new DeviceNotConnectedException("Thiết bị " + deviceId + " đang mất kết nối");
        try {
            connection.send(channel, json);
        } catch (IllegalStateException e) {
            throw new DeviceNotConnectedException("Không gửi được lệnh: " + e.getMessage());
        }
    }

    /** Trạng thái liên kết đổi (duyệt, gắn, thu hồi): báo bo ngay. */
    public void linkChanged(String deviceId) {
        DeviceConnection connection = byDevice.get(deviceId);
        if (connection != null) refresh(connection);
        DeviceConnection waiting = refused.remove(deviceId);
        if (waiting != null) {
            Session session = sessions.get(waiting);
            if (session != null) session.authFailedAt = 0;
            write(waiting, LinkState.of(LinkState.State.HELLO_REQUIRED, deviceId));
        }
    }

    /** Bo coi liên kết là mất khi 30 s không nhận được gì: gửi lại trạng thái mỗi 10 s. */
    @Scheduled(fixedDelayString = "${app.mqtt.keepalive-ms:10000}")
    public void keepalive() {
        byDevice.values().forEach(this::refresh);
    }

    // ---- Bắt tay ----

    private void onHello(DeviceConnection connection, Session session, String payload) {
        Hello hello;
        try {
            hello = mapper.readValue(payload, Hello.class);
        } catch (JacksonException e) {
            return;
        }
        String deviceId = DeviceIds.normalize(hello.deviceId());
        if (!DeviceIds.isValid(deviceId)) return;
        if (!connection.deviceId().equals(deviceId)) {
            // ACL gắn phiên với client id khác: bo giả danh bo khác.
            log.warn("[LINK] {} sent HELLO as {}; refused", connection.deviceId(), deviceId);
            write(connection, LinkState.of(LinkState.State.AUTH_FAILED, connection.deviceId()));
            return;
        }
        if (session.authFailedAt != 0 && System.currentTimeMillis() - session.authFailedAt < AUTH_RETRY_MS) return;
        Optional<byte[]> key = DeviceKeys.decodePublicKey(hello.publicKey());
        if (key.isEmpty()) {
            session.authFailedAt = System.currentTimeMillis();
            write(connection, LinkState.of(LinkState.State.AUTH_FAILED, deviceId));
            return;
        }
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
            proof = mapper.readValue(payload, Proof.class);
        } catch (JacksonException e) {
            return;
        }
        if (!nonce.equals(proof.nonce())) return;   // trả lời challenge cũ: chờ cái hiện tại
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
        accept(connection, session, deviceId, hello, key);
    }

    private void accept(DeviceConnection connection, Session session, String deviceId, Hello hello, byte[] key) {
        DeviceConnection other = byDevice.get(deviceId);
        if (other != null && other != connection && sessions.containsKey(other)) {
            write(connection, LinkState.of(LinkState.State.DUPLICATE, deviceId));
            return;
        }
        DeviceRegistry.HelloResult result = registry.getObject()
                .onHello(deviceId, hello.firmwareVersion(), hello.deviceType(), key, hello.ip(), hello.rssi());
        if (result != DeviceRegistry.HelloResult.OK) {
            session.authFailedAt = System.currentTimeMillis();
            refused.put(deviceId, connection);
            write(connection, LinkState.of(result == DeviceRegistry.HelloResult.REVOKED
                    ? LinkState.State.REVOKED : LinkState.State.AUTH_FAILED, deviceId));
            return;
        }
        session.authFailedAt = 0;
        refused.remove(deviceId, connection);
        session.deviceId = deviceId;
        byDevice.put(deviceId, connection);
        LinkState link = registry.getObject().linkState(deviceId);
        session.link = link;
        registry.getObject().connectivity(deviceId, true);
        log.info("[LINK] {} is {} (fw {})", deviceId, link.state(), hello.firmwareVersion());
        write(connection, link);
    }

    private void refresh(DeviceConnection connection) {
        Session session = sessions.get(connection);
        if (session == null || session.deviceId == null) return;
        try {
            LinkState link = registry.getObject().linkState(session.deviceId);
            session.link = link;
            write(connection, link);
            if (link.state() == LinkState.State.REVOKED) accounts.getObject().disable(session.deviceId);
        } catch (RuntimeException e) {
            log.warn("[LINK] cannot refresh {}: {}", session.deviceId, e.getMessage());
        }
    }

    private void release(String deviceId, DeviceConnection connection) {
        if (byDevice.remove(deviceId, connection)) {
            try {
                registry.getObject().connectivity(deviceId, false);
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
            connection.send(channel, mapper.writeValueAsString(body));
        } catch (RuntimeException e) {
            log.warn("[LINK] cannot publish {} to {}: {}", channel, connection.deviceId(), e.getMessage());
        }
    }
}
