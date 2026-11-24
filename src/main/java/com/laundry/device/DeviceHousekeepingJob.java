package com.laundry.device;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "app.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DeviceHousekeepingJob {

    private final DeviceCommandService commands;
    private final DeviceMessageLog messages;
    private final MachineDeploymentService deployment;

    @Scheduled(fixedDelay = 30_000)
    public void timeoutCommands() {
        commands.timeoutStale();
        deployment.timeoutSelfTests();
        deployment.startScheduledMaintenance();
    }

    /** Nhật ký kỹ thuật của bo chỉ giữ 30 ngày. */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Ho_Chi_Minh")
    @Transactional
    public void purgeMessages() {
        messages.purgeOlderThanDays(30);
    }
}
