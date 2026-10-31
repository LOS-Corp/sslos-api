package com.laundry.mqtt.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class MqttConfig {

    public static final String MQTT_EXECUTOR = "mqttTaskExecutor";

    /**
     * Dedicated pool for incoming device messages, so the Paho network thread is never
     * blocked and MQTT load is isolated from the rest of the application's async work.
     */
    @Bean(name = MQTT_EXECUTOR)
    public Executor mqttTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mqtt-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
