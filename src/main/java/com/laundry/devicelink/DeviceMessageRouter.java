package com.laundry.devicelink;

import com.laundry.devicelink.handler.AuthChannelHandler;
import com.laundry.devicelink.handler.WasherMessageHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Dispatches incoming device frames to their handler on the device worker pool,
 * off the serial reader thread.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceMessageRouter {

    private final WasherMessageHandler washerHandler;
    private final AuthChannelHandler authHandler;

    @Async(DeviceLinkConfig.DEVICE_EXECUTOR)
    public void route(UUID branchId, String machineCode, DeviceChannel channel, String payload) {
        if (channel == DeviceChannel.TELEMETRY || channel == DeviceChannel.HEARTBEAT) {
            log.debug("DEVICE RX branch={} machine={} channel={}", branchId, machineCode, channel);
        } else {
            log.info("DEVICE RX branch={} machine={} channel={}", branchId, machineCode, channel);
        }
        try {
            switch (channel) {
                case STATUS -> washerHandler.handleStatus(branchId, machineCode, payload);
                case TELEMETRY -> washerHandler.handleTelemetry(branchId, machineCode, payload);
                case HEARTBEAT -> washerHandler.handleHeartbeat(branchId, machineCode, payload);
                case EVENT -> washerHandler.handleEvent(branchId, machineCode, payload);
                case COMMAND_ACK -> washerHandler.handleCommandAck(branchId, machineCode, payload);
                case AUTH -> authHandler.handle(branchId, machineCode, payload);
            }
        } catch (RuntimeException e) {
            log.error("Failed to handle device frame branch={} machine={} channel={}",
                    branchId, machineCode, channel, e);
        }
    }
}
