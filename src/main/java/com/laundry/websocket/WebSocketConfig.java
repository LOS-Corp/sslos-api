package com.laundry.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Spring WebSocket + STOMP configuration.
 *
 * <p>Frontend clients connect to the STOMP endpoint and subscribe to topics.
 * The backend publishes machine updates to those topics via SimpMessagingTemplate.
 *
 * <p>Flow: ESP32 → MQTT → EMQX → Spring Boot → SimpMessagingTemplate → WebSocket → Client
 *
 * <p>Destinations:
 * <ul>
 *   <li>{@code /topic/machines}          — all machine updates
 *   <li>{@code /topic/machines/{code}}   — specific machine updates
 *   <li>{@code /topic/machines/{code}/telemetry} — telemetry stream
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // Enable simple in-memory broker for /topic and /queue destinations
        config.enableSimpleBroker("/topic", "/queue");
        // Prefix for messages FROM client (e.g., /app/ping → not used for IoT push)
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Main WebSocket endpoint — clients connect here (both raw WS and SockJS)
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*");
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();                  // SockJS fallback for browsers without WS support
    }
}
