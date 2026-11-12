package com.laundry.devicelink;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class DeviceLinkConfig {

    public static final String DEVICE_EXECUTOR = "deviceTaskExecutor";

    /**
     * Dedicated pool for incoming device frames, so the serial reader threads are
     * never blocked by database work and device load stays isolated from the rest
     * of the application's async work.
     */
    @Bean(name = DEVICE_EXECUTOR)
    public Executor deviceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("device-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
