package com.laundry.mqtt.service;

import com.laundry.mqtt.config.MqttProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the lifecycle of the MQTT connection to EMQX Cloud.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Connect on startup (called by {@link MqttLifecycleBean}).
 *   <li>Reconnect automatically if the connection is lost.
 *   <li>Re-subscribe to all topics after reconnect.
 *   <li>Disconnect cleanly on shutdown.
 * </ol>
 *
 * <p>The MQTT callback is kept lightweight: it delegates message routing to
 * {@link MqttMessageRouter} which dispatches to the appropriate handler on a
 * separate thread pool — never blocking the Paho network thread.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MqttConnectionManager implements MqttCallbackExtended {

    private final MqttProperties mqttProperties;
    private final MqttMessageRouter messageRouter;
    private final MqttSubscriber mqttSubscriber;

    private MqttClient mqttClient;
    private final AtomicBoolean connecting = new AtomicBoolean(false);

    // ── Connection lifecycle ──────────────────────────────────────────────────

    /**
     * Connect to EMQX Cloud. Called on application startup.
     * Thread-safe: will not attempt duplicate connections.
     */
    public synchronized void connect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            log.debug("MQTT already connected — skipping connect()");
            return;
        }
        if (connecting.getAndSet(true)) {
            log.debug("MQTT connection already in progress");
            return;
        }

        String brokerUri = mqttProperties.buildBrokerUri();
        log.info("Connecting to MQTT broker: {} (clientId={})", brokerUri, mqttProperties.getClientId());

        try {
            mqttClient = new MqttClient(brokerUri, mqttProperties.getClientId(), new MemoryPersistence());
            mqttClient.setCallback(this);

            MqttConnectOptions options = buildConnectOptions();
            mqttClient.connect(options);

            log.info("✅ Connected to EMQX broker at {}", brokerUri);
            mqttSubscriber.subscribeAll(mqttClient);

        } catch (MqttException e) {
            log.error("❌ Failed to connect to MQTT broker {}: {}", brokerUri, e.getMessage(), e);
        } finally {
            connecting.set(false);
        }
    }

    private MqttConnectOptions buildConnectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();

        String username = mqttProperties.getUsername();
        String password = mqttProperties.getPassword();

        if (username != null && !username.isBlank()) {
            options.setUserName(username);
        }
        if (password != null && !password.isBlank()) {
            options.setPassword(password.toCharArray());
        }

        options.setCleanSession(mqttProperties.isCleanSession());
        options.setConnectionTimeout(mqttProperties.getConnectionTimeoutSeconds());
        options.setKeepAliveInterval(mqttProperties.getKeepAliveIntervalSeconds());
        options.setAutomaticReconnect(true);  // Paho built-in reconnect (with backoff)

        // For TLS (ssl://), Paho uses the JVM trust store by default.
        // EMQX Cloud uses a CA-signed certificate, which Java trusts out of the box.
        // For self-signed certs, configure a custom SSLSocketFactory here.

        return options;
    }

    // ── MqttCallback implementation ───────────────────────────────────────────

    /**
     * Called when the connection is lost. Paho will attempt automatic reconnect
     * because setAutomaticReconnect(true) is set. After reconnect, connectComplete()
     * is called and we re-subscribe.
     */
    @Override
    public void connectionLost(Throwable cause) {
        log.warn("⚠️ MQTT connection lost: {}. Paho automatic reconnect is active.", cause.getMessage());
    }

    /**
     * Called when Paho successfully (re)connects. We must re-subscribe here
     * because a clean session loses all subscriptions on reconnect.
     */
    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        if (reconnect) {
            log.info("✅ MQTT reconnected to {}. Re-subscribing to topics...", serverURI);
            mqttSubscriber.subscribeAll(mqttClient);
        }
    }

    /**
     * Called by Paho on the network thread when a message arrives.
     * MUST be fast — delegate immediately to the async router.
     */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Paho network thread — do NOT block here
        messageRouter.route(topic, message);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Called when a QoS 1/2 publish is confirmed; not used here
    }

    // ── Publisher access ──────────────────────────────────────────────────────

    /**
     * Returns the active MQTT client for publishing.
     * @throws IllegalStateException if not connected
     */
    public MqttClient getClient() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            throw new IllegalStateException("MQTT client is not connected");
        }
        return mqttClient;
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }

    @PreDestroy
    public void disconnect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                log.info("Disconnecting MQTT client...");
                mqttClient.disconnect();
                mqttClient.close();
                log.info("MQTT client disconnected cleanly.");
            } catch (MqttException e) {
                log.warn("Error during MQTT disconnect: {}", e.getMessage());
            }
        }
    }
}
