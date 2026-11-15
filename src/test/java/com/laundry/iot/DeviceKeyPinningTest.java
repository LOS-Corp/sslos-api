package com.laundry.iot;

import com.laundry.devicelink.DeviceIdentity;
import com.laundry.devicelink.DeviceKeys;
import com.laundry.devicelink.DeviceLink;
import com.laundry.devicelink.DeviceLinkChanged;
import com.laundry.devicelink.LinkState;
import com.laundry.devicelink.TestDeviceKey;
import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.iot.service.DeviceRegistryService;
import com.laundry.machine.repository.DeviceEventRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineService;
import com.laundry.machine.service.MachineWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wi-Fi onboarding: the first key of a board that is not approved yet is pinned; any other key
 * (reflashed board, clone, a USB-approved board joining Wi-Fi) is refused until an admin trusts it.
 */
class DeviceKeyPinningTest {

    private static final String DEVICE = "ESP-24A1B2C3D4E5";

    private final Map<String, IotDevice> devices = new HashMap<>();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private DeviceRegistryService service;

    @BeforeEach
    void setUp() {
        IotDeviceRepository repository = mock(IotDeviceRepository.class);
        when(repository.findByDeviceIdForUpdate(anyString()))
                .thenAnswer(call -> Optional.ofNullable(devices.get(call.<String>getArgument(0))));
        when(repository.findByDeviceId(anyString()))
                .thenAnswer(call -> Optional.ofNullable(devices.get(call.<String>getArgument(0))));
        when(repository.findByKeyFingerprint(anyString())).thenAnswer(call -> devices.values().stream()
                .filter(d -> Objects.equals(d.getKeyFingerprint(), call.getArgument(0))).findFirst());
        when(repository.save(any(IotDevice.class))).thenAnswer(call -> {
            IotDevice device = call.getArgument(0);
            devices.put(device.getDeviceId(), device);
            return device;
        });
        service = new DeviceRegistryService(repository, mock(MachineRepository.class), mock(DeviceEventRepository.class),
                mock(MachineWebSocketBroadcaster.class), mock(MachineService.class), mock(DeviceLink.class), events);
    }

    private static DeviceIdentity identity(TestDeviceKey key) {
        return new DeviceIdentity(key.raw, "192.168.1.23", -61);
    }

    @Test
    void aNewBoardsFirstKeyIsPinnedAndItWaitsForApproval() {
        TestDeviceKey key = new TestDeviceKey();

        LinkState link = service.onHello(DEVICE, "5.0.0", "WASHER", identity(key));

        assertThat(link.state()).isEqualTo(LinkState.State.PENDING);
        IotDevice device = devices.get(DEVICE);
        assertThat(device.getKeyFingerprint()).isEqualTo(DeviceKeys.fingerprint(key.raw));
        assertThat(device.getLastIp()).isEqualTo("192.168.1.23");
        assertThat(device.getLastRssi()).isEqualTo(-61);
        assertThat(service.onHello(DEVICE, "5.0.0", "WASHER", identity(key)).state()).isEqualTo(LinkState.State.PENDING);
    }

    @Test
    void anotherKeyForAPinnedBoardIsRefusedAndParked() {
        TestDeviceKey genuine = new TestDeviceKey();
        service.onHello(DEVICE, "5.0.0", "WASHER", identity(genuine));
        devices.get(DEVICE).setStatus(IotDeviceStatus.VERIFIED);

        TestDeviceKey clone = new TestDeviceKey();
        LinkState link = service.onHello(DEVICE, "evil", "DRYER", identity(clone));

        assertThat(link.state()).isEqualTo(LinkState.State.AUTH_FAILED);
        IotDevice device = devices.get(DEVICE);
        assertThat(device.getKeyFingerprint()).isEqualTo(DeviceKeys.fingerprint(genuine.raw));
        assertThat(device.getPendingKeyFingerprint()).isEqualTo(DeviceKeys.fingerprint(clone.raw));
        assertThat(device.getAuthFailures()).isEqualTo(1);
        assertThat(device.getFirmwareVersion()).isEqualTo("5.0.0");   // nothing from the refused HELLO is kept
        assertThat(device.getStatus()).isEqualTo(IotDeviceStatus.VERIFIED);
        // The genuine board still gets in.
        assertThat(service.onHello(DEVICE, "5.0.0", "WASHER", identity(genuine)).state()).isEqualTo(LinkState.State.VERIFIED);
    }

    @Test
    void aBoardApprovedBeforeItHadAKeyMustHaveItsFirstKeyTrusted() {
        devices.put(DEVICE, IotDevice.builder().deviceId(DEVICE).status(IotDeviceStatus.VERIFIED).build());
        TestDeviceKey key = new TestDeviceKey();

        assertThat(service.onHello(DEVICE, "5.0.0", "WASHER", identity(key)).state()).isEqualTo(LinkState.State.AUTH_FAILED);

        var summary = service.trustPendingKey(DEVICE, UUID.randomUUID());
        assertThat(summary.keyFingerprint()).isEqualTo(DeviceKeys.shortFingerprint(DeviceKeys.fingerprint(key.raw)));
        assertThat(summary.pendingKeyFingerprint()).isNull();
        assertThat(summary.authFailures()).isZero();
        verify(events).publishEvent(new DeviceLinkChanged(DEVICE));
        assertThat(service.onHello(DEVICE, "5.0.0", "WASHER", identity(key)).state()).isEqualTo(LinkState.State.VERIFIED);
    }

    @Test
    void oneKeyCannotIdentifyTwoBoards() {
        TestDeviceKey key = new TestDeviceKey();
        service.onHello(DEVICE, "5.0.0", "WASHER", identity(key));

        LinkState link = service.onHello("ESP-000000000001", "5.0.0", "WASHER", identity(key));

        assertThat(link.state()).isEqualTo(LinkState.State.AUTH_FAILED);
        assertThat(devices.get("ESP-000000000001").getKeyFingerprint()).isNull();
        assertThatThrownBy(() -> service.trustPendingKey("ESP-000000000001", UUID.randomUUID()))
                .isInstanceOf(ProvisioningRejectedException.class);
    }

    @Test
    void trustingWithoutAPendingKeyIsRejected() {
        service.onHello(DEVICE, "5.0.0", "WASHER", identity(new TestDeviceKey()));

        assertThatThrownBy(() -> service.trustPendingKey(DEVICE, UUID.randomUUID()))
                .isInstanceOf(ProvisioningRejectedException.class);
    }
}
