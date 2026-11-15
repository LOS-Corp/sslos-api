package com.laundry.devicelink.mqtt;

import com.laundry.devicelink.DeviceConnection;
import com.laundry.devicelink.DeviceFrame;
import com.laundry.devicelink.DeviceIds;
import com.laundry.devicelink.DeviceLinkHub;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The device link's transport: the backend's session on the store's local EMQX broker.
 * Each board's MQTT session becomes one {@link DeviceConnection} of the {@link DeviceLinkHub}; every
 * {@code up/<channel>} message is handed to the hub as (channel, JSON). Topic tree: {@link MqttTopics}.
 *
 * <p>Works with no Internet: broker, backend and boards are all on the store LAN. Each board logs in with its own
 * broker account and may only use its own topics ({@code emqx/acl.conf}); on top of that the hub makes every new
 * session sign a nonce with the board's key, so a leaked password alone does not impersonate a board.
 *
 * <p>All inbound work runs on one thread, in arrival order, off the Paho callback thread.
 */
@Component
@ConditionalOnProperty(prefix = "app.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class MqttDeviceTransport implements MqttCallbackExtended {

    private static final long RETRY_MS = 5000;
    private static final long PUBLISH_WAIT_MS = 5000;
    private static final int KEEPALIVE_SECONDS = 20;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int MAX_INFLIGHT = 200;
    private static final long DISCONNECT_WAIT_MS = 2000;

    private final DeviceLinkHub hub;
    private final MqttLinkProperties properties;
    private final DeviceTrafficLog traffic;
    private final ObjectProvider<BrokerAccounts> accounts;

    private final Map<String, BoardSession> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-link");
        t.setDaemon(true);
        return t;
    });
    private volatile MqttClient client;
    private volatile boolean stopping;
    private volatile boolean reportedDown;

    public MqttDeviceTransport(DeviceLinkHub hub, MqttLinkProperties properties, DeviceTrafficLog traffic,
                               ObjectProvider<BrokerAccounts> accounts) {
        this.hub = hub;
        this.properties = properties;
        this.traffic = traffic;
        this.accounts = accounts;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        worker.execute(this::connect);
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        worker.shutdownNow();
        MqttClient c = client;
        if (c != null) {
            try {
                if (c.isConnected()) c.disconnect(DISCONNECT_WAIT_MS);
                c.close();
            } catch (MqttException e) {
                log.debug("[MQTT] closing: {}", e.getMessage());
            }
        }
    }

    /** True while the backend's own broker session is up (shown on the admin page). */
    public boolean isBrokerConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    /**
     * First connection, retried until the broker answers: Paho's automatic reconnect only takes over
     * after one successful connect (e.g. the backend started before EMQX).
     */
    private void connect() {
        if (stopping) return;
        // The backend's own broker user comes from the configuration: (re)create it before logging in.
        accounts.ifAvailable(BrokerAccounts::ensureBackendUser);
        try {
            if (client == null) {
                String clientId = properties.getClientId() == null || properties.getClientId().isBlank()
                        ? "laundryos-server-" + UUID.randomUUID().toString().substring(0, 12)
                        : properties.getClientId();
                client = new MqttClient(properties.getBrokerUrl(), clientId, new MemoryPersistence());
                client.setCallback(this);
                client.setTimeToWait(PUBLISH_WAIT_MS);
            }
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setKeepAliveInterval(KEEPALIVE_SECONDS);
            options.setConnectionTimeout(CONNECT_TIMEOUT_SECONDS);
            options.setMaxInflight(MAX_INFLIGHT);
            options.setUserName(properties.getUsername());
            options.setPassword(properties.getPassword() == null ? new char[0] : properties.getPassword().toCharArray());
            client.connect(options);
        } catch (MqttException e) {
            if (!reportedDown) {
                reportedDown = true;
                log.warn("[MQTT] Cannot reach the broker at {} ({}); retrying every {} s. Boards stay offline until then.",
                        properties.getBrokerUrl(), e.getMessage(), RETRY_MS / 1000);
            }
            if (!stopping) worker.schedule(this::connect, RETRY_MS, TimeUnit.MILLISECONDS);
        }
    }

    // ── Paho callbacks (never block here) ──────────────────────────────────

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        reportedDown = false;
        log.info("[MQTT] {} to {}", reconnect ? "Reconnected" : "Connected", serverUri);
        worker.execute(this::subscribe);
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("[MQTT] Lost the broker: {}; boards are offline until it is back", cause == null ? "?" : cause.getMessage());
        worker.execute(this::closeAll);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        byte[] payload = message.getPayload();
        boolean retained = message.isRetained();
        worker.execute(() -> handle(topic, payload, retained));
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // QoS bookkeeping only.
    }

    // ── Inbound (worker thread) ────────────────────────────────────────────

    private void subscribe() {
        try {
            // Presence is retained: subscribing replays which boards are online right now.
            client.subscribe(new String[]{MqttTopics.SUBSCRIBE_UP, MqttTopics.SUBSCRIBE_PRESENCE}, new int[]{0, 1});
        } catch (MqttException e) {
            log.error("[MQTT] Cannot subscribe to the board topics: {}", e.getMessage());
        }
    }

    void handle(String topic, byte[] payload, boolean retained) {
        MqttTopics.Inbound inbound = MqttTopics.parse(topic).orElse(null);
        if (inbound == null) return;
        String deviceId = inbound.deviceId();
        if (!DeviceIds.isValid(deviceId)) {
            log.debug("[MQTT] ignoring topic of a non-LaundryOS client: {}", topic);
            return;
        }
        if (payload.length > DeviceFrame.MAX_PAYLOAD) {
            log.warn("[MQTT] {} sent {} bytes on {}; larger than {}: dropped", deviceId, payload.length, topic, DeviceFrame.MAX_PAYLOAD);
            return;
        }
        String text = new String(payload, StandardCharsets.UTF_8).strip();
        if (inbound.isPresence()) {
            if (MqttTopics.ONLINE.equals(text)) {
                // A board announces every new MQTT session: start it unauthenticated, so nothing proven by an
                // earlier session (or by another client that used the same id) carries over.
                if (!retained) close(deviceId);
                open(deviceId);
            } else if (MqttTopics.OFFLINE.equals(text)) {
                close(deviceId);   // last will: power loss, Wi-Fi loss, or its session was taken over
            }
            return;
        }
        if (retained || text.isEmpty()) return;  // a stale message left on the broker is not live traffic
        BoardSession connection = open(deviceId);
        traffic.record(deviceId, DeviceTrafficLog.Kind.UP, topic, text);
        try {
            hub.frameReceived(connection, inbound.channel(), text);
        } catch (RuntimeException e) {
            log.error("[MQTT] {} {} handling failed", deviceId, inbound.channel(), e);
        }
    }

    private BoardSession open(String deviceId) {
        BoardSession existing = connections.get(deviceId);
        if (existing != null) return existing;
        BoardSession connection = new BoardSession(deviceId);
        connections.put(deviceId, connection);
        traffic.record(deviceId, DeviceTrafficLog.Kind.INFO, MqttTopics.presence(deviceId), MqttTopics.ONLINE);
        hub.connected(connection);
        return connection;
    }

    private void close(String deviceId) {
        BoardSession connection = connections.remove(deviceId);
        if (connection == null) return;
        traffic.record(deviceId, DeviceTrafficLog.Kind.INFO, MqttTopics.presence(deviceId), MqttTopics.OFFLINE);
        hub.disconnected(connection);
    }

    private void closeAll() {
        List.copyOf(connections.keySet()).forEach(this::close);
    }

    /** One board's MQTT session as seen by the hub. */
    final class BoardSession implements DeviceConnection {

        private final String deviceId;

        BoardSession(String deviceId) {
            this.deviceId = deviceId;
        }

        @Override
        public String deviceId() {
            return deviceId;
        }

        @Override
        public void send(String channel, String json) {
            MqttClient c = client;
            if (c == null || !c.isConnected()) throw new IllegalStateException("MQTT broker is not connected");
            String topic = MqttTopics.down(deviceId, channel);
            traffic.record(deviceId, DeviceTrafficLog.Kind.DOWN, topic, json);
            try {
                MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
                message.setQos(Math.max(0, Math.min(1, properties.getQos())));
                message.setRetained(false);
                c.publish(topic, message);
            } catch (MqttException e) {
                throw new IllegalStateException("Publish to " + topic + " failed: " + e.getMessage(), e);
            }
        }

        @Override
        public String toString() {
            return "mqtt:" + deviceId;
        }
    }
}
