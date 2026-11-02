package com.laundry.websocket;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
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
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Value("${app.cors.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }

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
        String[] origins = allowedOrigins();
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins);
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS();                  // SockJS fallback for browsers without WS support
    }

    /** Same origins as the REST CORS policy; "*" only when none are configured (local development). */
    private String[] allowedOrigins() {
        if (allowedOrigins == null || allowedOrigins.isBlank()) {
            return new String[] {"*"};
        }
        return java.util.Arrays.stream(allowedOrigins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    }
}
