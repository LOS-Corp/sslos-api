package com.laundry.iot.service;

import com.laundry.devicelink.DeviceFrame;
import com.laundry.devicelink.DeviceLink;
import com.laundry.devicelink.mqtt.MqttDeviceTransport;
import com.laundry.devicelink.mqtt.MqttLinkProperties;
import com.laundry.iot.dto.ProvisioningDtos.WifiSetup;
import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * Wi-Fi side of the boards: what the admin types into a board's setup page, and the deliberate
 * reset that sends a board back to setup mode ({@code LaundryOS-Setup-XXXX}). A board never re-opens
 * its setup access point by itself when the store Wi-Fi or the broker is down; it keeps reconnecting.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeviceNetworkService {

    private final IotDeviceRepository deviceRepository;
    private final DeviceLink deviceLink;
    private final DeviceIdleGuard idleGuard;
    private final MqttLinkProperties mqtt;
    private final ObjectProvider<MqttDeviceTransport> transport;

    /** What a board's setup page needs; {@code null} when the MQTT link is off. */
    public WifiSetup wifiSetup() {
        if (!mqtt.isEnabled()) return null;
        MqttDeviceTransport t = transport.getIfAvailable();
        return new WifiSetup(mqtt.getDeviceHost(), mqtt.getDeviceApiPort(), mqtt.getDevicePort(),
                t != null && t.isBrokerConnected());
    }

    /**
     * Tells a board to forget the store Wi-Fi and broker and restart in setup mode. Its approval,
     * pinned key and machine binding are kept: after the new Wi-Fi is entered it comes back as itself.
     */
    public void resetWifi(String rawDeviceId, UUID adminId) {
        String deviceId = rawDeviceId.trim().toUpperCase(Locale.ROOT);
        IotDevice device = deviceRepository.findByDeviceId(deviceId)
                .orElseThrow(() -> new ProvisioningRejectedException(HttpStatus.NOT_FOUND, "Unknown device " + deviceId));
        if (!deviceLink.isConnected(deviceId)) {
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, deviceId + " is not connected; "
                    + "reset it on the board (hold BOOT for 5 s)");
        }
        idleGuard.requireIdle(device);
        try {
            deviceLink.sendFrame(deviceId, DeviceFrame.NETWORK, "{\"action\":\"RESET\"}");
        } catch (IllegalStateException e) {  // includes DeviceNotConnectedException
            throw new ProvisioningRejectedException(HttpStatus.CONFLICT, "Cannot reach " + deviceId + ": " + e.getMessage());
        }
        log.warn("[IOT] Admin {} sent {} back to Wi-Fi setup mode", adminId, deviceId);
    }
}
