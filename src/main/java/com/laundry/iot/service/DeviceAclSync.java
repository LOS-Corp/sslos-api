package com.laundry.iot.service;

import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.mqtt.service.MqttTopicBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Re-applies every provisioned device's ACL at startup, so a channel added to
 * the device ACL (e.g. {@code log}) reaches boards that were activated before.
 * Idempotent; in MANUAL mode it only says which rule to add in the EMQX console.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DeviceAclSync {
    private final IotDeviceRepository deviceRepository;
    private final MachineRepository machineRepository;
    private final EmqxAdminClient emqx;
    private final MqttTopicBuilder topics;

    @EventListener(ApplicationReadyEvent.class)
    public void syncAcls() {
        if (emqx.isManual()) {
            log.info("EMQX MANUAL mode: for device debug logs, allow each device user to publish"
                    + " on {}/{{branchId}}/{{machineCode}}/{}", MqttTopicBuilder.ROOT, MqttTopicBuilder.LOG);
            return;
        }
        int synced = 0;
        for (var device : deviceRepository.findAllByOrderByEnrolledAtDesc()) {
            boolean hasCredentials = device.getStatus() == IotDeviceStatus.PROVISIONED
                    || device.getStatus() == IotDeviceStatus.ACTIVE;
            if (!hasCredentials || device.getMachineId() == null
                    || device.getMqttUsername() == null) continue;
            var machine = machineRepository.findById(device.getMachineId()).orElse(null);
            if (machine == null) continue;
            try {
                emqx.replaceDeviceAcl(device.getMqttUsername(), topics.baseTopic(machine.getBranchId(), machine.getMachineCode()));
                synced++;
            } catch (RuntimeException e) {
                log.warn("ACL sync failed for device {}: {}", device.getDeviceId(), e.getMessage());
            }
        }
        if (synced > 0) log.info("EMQX ACL re-applied for {} provisioned device(s)", synced);
    }
}
