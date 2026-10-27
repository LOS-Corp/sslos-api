package com.laundry.mqtt.service;

import com.laundry.mqtt.config.MqttProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Owns the single backend connection to the broker: connects once the application
 * is ready, (re)subscribes after every connect, and disconnects on shutdown.
 * Lost connections are recovered by Paho's automatic reconnect.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MqttConnectionManager implements MqttCallbackExtended {

    private static final int SUBSCRIBE_QOS = 1;

    private final MqttProperties properties;
    private final MqttMessageRouter router;

    private volatile MqttClient client;

    /** Runs after the whole context is ready, so every handler exists before the first message. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void connect() {
        if (!properties.isEnabled()) {
            log.info("MQTT disabled (app.mqtt.enabled=false); device messaging is off");
            return;
        }
        if (isConnected()) {
            return;
        }
        String brokerUri = properties.brokerUri();
        try {
            if (client == null) {
                client = new MqttClient(brokerUri, properties.getClientId(), new MemoryPersistence());
                client.setCallback(this);
            }
            log.info("Connecting to MQTT broker {} as {}", brokerUri, properties.getClientId());
            client.connect(connectOptions());
        } catch (MqttException e) {
            log.error("Failed to connect to MQTT broker {}", brokerUri, e);
        }
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    /** @throws IllegalStateException if the broker is not connected */
    public MqttClient client() {
        if (!isConnected()) {
            throw new IllegalStateException("MQTT broker is not connected");
        }
        return client;
    }

    @PreDestroy
    public synchronized void disconnect() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
            log.info("MQTT client disconnected");
        } catch (MqttException e) {
            log.warn("Error while disconnecting MQTT client: {}", e.getMessage());
        }
    }

    // Paho callbacks

    /** Called after the first connect and every reconnect; a clean session loses its subscriptions. */
    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        log.info("MQTT {} to {}", reconnect ? "reconnected" : "connected", serverUri);
        try {
            client.subscribe(MqttTopicBuilder.BACKEND_SUBSCRIPTION, SUBSCRIBE_QOS);
            log.info("Subscribed to {}", MqttTopicBuilder.BACKEND_SUBSCRIPTION);
        } catch (MqttException e) {
            log.error("Failed to subscribe to {}", MqttTopicBuilder.BACKEND_SUBSCRIPTION, e);
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}. Automatic reconnect is active.", cause.getMessage());
    }

    /** Runs on the Paho network thread: hand off immediately, never block here. */
    @Override
    public void messageArrived(String topic, MqttMessage message) {
        router.route(topic, message);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Delivery confirmations are not tracked.
    }

    private MqttConnectOptions connectOptions() {
        MqttConnectOptions options = new MqttConnectOptions();
        if (hasText(properties.getUsername())) {
            options.setUserName(properties.getUsername());
        }
        if (hasText(properties.getPassword())) {
            options.setPassword(properties.getPassword().toCharArray());
        }
        options.setCleanSession(properties.isCleanSession());
        options.setConnectionTimeout(properties.getConnectionTimeoutSeconds());
        options.setKeepAliveInterval(properties.getKeepAliveIntervalSeconds());
        options.setAutomaticReconnect(true);
        // TLS uses the JVM trust store, which already trusts EMQX Cloud's CA-signed certificate.
        return options;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
