package com.laundry.mqtt.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.laundry.mqtt.service.MqttConnectionManager;

import java.util.concurrent.Executor;

/**
 * Spring configuration for MQTT:
 * <ul>
 *   <li>Enables async processing (@EnableAsync) for the MQTT message router.
 *   <li>Enables scheduled tasks (@EnableScheduling) for the offline detector.
 *   <li>Defines the named task executor used by the router.
 *   <li>Triggers MQTT connection after application context is ready.
 * </ul>
 */
@Configuration
@EnableAsync
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class MqttConfig {

    private final MqttConnectionManager mqttConnectionManager;

    /**
     * Named thread pool for async MQTT message processing.
     *
     * <p>Using a dedicated executor (not Spring's default) ensures MQTT processing
     * is isolated from other application async tasks and can be tuned independently.
     *
     * <p>Core pool: 4 threads (handles burst of concurrent device messages).
     * Max pool: 16 threads (bursts from many devices simultaneously).
     * Queue capacity: 500 (buffer for message bursts during DB saturation).
     */
    @Bean(name = "mqttTaskExecutor")
    public Executor mqttTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mqtt-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        log.info("MQTT task executor configured: core=4, max=16, queue=500");
        return executor;
    }

    /**
     * Connect to EMQX after the entire Spring context is ready.
     * This ensures all beans (handlers, services, repositories) are initialized
     * before the first MQTT message could arrive.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application ready — initiating MQTT connection to EMQX...");
        mqttConnectionManager.connect();
    }
}
