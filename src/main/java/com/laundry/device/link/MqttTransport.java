package com.laundry.device.link;

import com.laundry.device.DeviceRegistry;
import com.laundry.device.MqttAccountService;
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
 * Phiên của backend trên EMQX cửa hàng. Mỗi phiên MQTT của một bo thành một {@link DeviceConnection} của hub.
 * Mọi tin đến xử lý trên một luồng duy nhất, theo thứ tự đến, ngoài luồng callback của Paho.
 */
@Component
@ConditionalOnProperty(prefix = "app.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class MqttTransport implements MqttCallbackExtended {

    private static final long RETRY_MS = 5000;

    private final DeviceLinkHub hub;
    private final MqttProperties properties;
    private final MqttAccountService accounts;
    private final DeviceRegistry registry;
    private final Map<String, BoardSession> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mqtt-link");
        t.setDaemon(true);
        return t;
    });
    private volatile MqttClient client;
    private volatile boolean stopping;
    private volatile boolean reportedDown;

    public MqttTransport(DeviceLinkHub hub, MqttProperties properties, MqttAccountService accounts,
                         DeviceRegistry registry) {
        this.hub = hub;
        this.properties = properties;
        this.accounts = accounts;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        // Backend vừa khởi động thì chưa biết bo nào online: presence (retained) sẽ báo lại.
        registry.allOffline();
        accounts.ensureBackendAccount(properties.username(), properties.password());
        worker.execute(this::connect);
    }

    @PreDestroy
    public void stop() {
        stopping = true;
        worker.shutdownNow();
        MqttClient c = client;
        if (c != null) {
            try {
                if (c.isConnected()) c.disconnect(2000);
                c.close();
            } catch (MqttException e) {
                log.debug("[MQTT] closing: {}", e.getMessage());
            }
        }
    }

    public boolean isBrokerConnected() {
        MqttClient c = client;
        return c != null && c.isConnected();
    }

    /** Lần kết nối đầu, thử lại tới khi broker trả lời (Paho chỉ tự nối lại sau một lần thành công). */
    private void connect() {
        if (stopping) return;
        try {
            if (client == null) {
                client = new MqttClient(properties.brokerUrl(), "sslos-backend-" + UUID.randomUUID().toString()
                        .substring(0, 8), new MemoryPersistence());
                client.setCallback(this);
                client.setTimeToWait(5000);
            }
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);
            options.setKeepAliveInterval(20);
            options.setConnectionTimeout(10);
            options.setMaxInflight(200);
            options.setUserName(properties.username());
            options.setPassword(properties.password() == null ? new char[0] : properties.password().toCharArray());
            client.connect(options);
        } catch (MqttException e) {
            if (!reportedDown) {
                reportedDown = true;
                log.warn("[MQTT] Cannot reach the broker at {} ({}); retrying every {} s", properties.brokerUrl(),
                        e.getMessage(), RETRY_MS / 1000);
            }
            if (!stopping) worker.schedule(this::connect, RETRY_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        reportedDown = false;
        log.info("[MQTT] {} to {}", reconnect ? "Reconnected" : "Connected", serverUri);
        worker.execute(this::subscribe);
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("[MQTT] Lost the broker: {}", cause == null ? "?" : cause.getMessage());
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
    }

    private void subscribe() {
        try {
            client.subscribe(new String[]{MqttTopics.SUBSCRIBE_UP, MqttTopics.SUBSCRIBE_PRESENCE}, new int[]{0, 1});
        } catch (MqttException e) {
            log.error("[MQTT] Cannot subscribe: {}", e.getMessage());
        }
    }

    void handle(String topic, byte[] payload, boolean retained) {
        MqttTopics.Inbound in = MqttTopics.parse(topic).orElse(null);
        if (in == null || !DeviceIds.isValid(in.deviceId()) || payload.length > DeviceFrame.MAX_PAYLOAD) return;
        String text = new String(payload, StandardCharsets.UTF_8).strip();
        String deviceId = in.deviceId();
        try {
            if (in.isPresence()) {
                if (MqttTopics.ONLINE.equals(text)) {
                    // Mỗi phiên mới bắt đầu chưa xác thực: không mang theo gì từ phiên trước.
                    if (!retained) close(deviceId);
                    open(deviceId);
                } else if (MqttTopics.OFFLINE.equals(text)) {
                    close(deviceId);
                }
                return;
            }
            if (retained || text.isEmpty()) return;
            hub.frameReceived(open(deviceId), in.channel(), text);
        } catch (RuntimeException e) {
            log.error("[MQTT] {} {} handling failed", deviceId, topic, e);
        }
    }

    private BoardSession open(String deviceId) {
        return connections.computeIfAbsent(deviceId, id -> {
            BoardSession s = new BoardSession(id);
            hub.connected(s);
            return s;
        });
    }

    private void close(String deviceId) {
        BoardSession s = connections.remove(deviceId);
        if (s != null) hub.disconnected(s);
    }

    private void closeAll() {
        List.copyOf(connections.keySet()).forEach(this::close);
    }

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
            try {
                MqttMessage m = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
                m.setQos(1);
                c.publish(MqttTopics.down(deviceId, channel), m);
            } catch (MqttException e) {
                throw new IllegalStateException("Publish failed: " + e.getMessage(), e);
            }
        }
    }
}
