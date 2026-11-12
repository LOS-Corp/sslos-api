package com.laundry.iot.firmware;

import com.laundry.devicelink.DeviceLink;
import com.laundry.devicelink.SerialPortControl;
import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.firmware.FirmwareUpdateStatus.State;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.repository.MachineStartAuthorizationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FirmwareUpdateServiceTest {

    private static final String DEVICE = "ESP-3076F591F738";
    private static final byte[] WASHER_IMAGE = FirmwareImageTest.image("LAUNDRYOS-FW|WASHER|usb-4.1.0", 4096);

    private IotDevice device;
    private Machine machine;
    private DeviceLink link;
    private SerialPortControl ports;
    private MachineStartAuthorizationRepository authorizations;
    private final AtomicBoolean rebooted = new AtomicBoolean();
    private FirmwareFlasher flasher;
    private FirmwareUpdateService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        machine = Machine.builder().id(UUID.randomUUID()).machineCode("WM01").type(MachineType.WASHER)
                .status(MachineStatus.AVAILABLE).build();
        device = IotDevice.builder().deviceId(DEVICE).deviceType(MachineType.WASHER).status(IotDeviceStatus.ACTIVE)
                .machineId(machine.getId()).firmwareVersion("usb-4.0.0").build();
        IotDeviceRepository devices = mock(IotDeviceRepository.class);
        when(devices.findByDeviceId(DEVICE)).thenReturn(Optional.of(device));
        MachineRepository machines = mock(MachineRepository.class);
        when(machines.findById(machine.getId())).thenReturn(Optional.of(machine));
        authorizations = mock(MachineStartAuthorizationRepository.class);
        when(authorizations.findActiveByMachine(any())).thenReturn(Optional.empty());

        link = mock(DeviceLink.class);
        when(link.portOf(DEVICE)).thenReturn("COM3");
        // Back online once the "board" rebooted, as the scanner + HELLO would do.
        when(link.isConnected(DEVICE)).thenAnswer(call -> rebooted.get());
        ports = mock(SerialPortControl.class);
        ObjectProvider<SerialPortControl> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(ports);

        // The fake esptool prints progress and "reboots" the board into the new version.
        flasher = (port, image, output) -> {
            output.accept("Writing at 0x00010000... (50 %)");
            output.accept("Writing at 0x00050000... (100 %)");
            device.setFirmwareVersion("usb-4.1.0");
            rebooted.set(true);
            return 0;
        };
        FirmwareProperties properties = new FirmwareProperties();
        properties.setReleaseDelayMs(0);
        properties.setRebootTimeoutSeconds(2);
        service = new FirmwareUpdateService(devices, machines, authorizations, link, provider,
                (port, image, output) -> flasher.flash(port, image, output), properties);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private FirmwareUpdateStatus awaitFinished() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            FirmwareUpdateStatus status = service.status(DEVICE).orElseThrow();
            if (status.finished()) return status;
            Thread.sleep(50);
        }
        throw new AssertionError("flashing did not finish");
    }

    @Test
    void flashesThroughAReleasedPortAndWaitsForTheNewVersion() throws Exception {
        FirmwareUpdateStatus started = service.start(DEVICE.toLowerCase(), WASHER_IMAGE, UUID.randomUUID());
        assertThat(started.state()).isEqualTo(State.FLASHING);
        assertThat(started.toVersion()).isEqualTo("usb-4.1.0");

        FirmwareUpdateStatus done = awaitFinished();

        assertThat(done.state()).isEqualTo(State.DONE);
        assertThat(done.progress()).isEqualTo(100);
        assertThat(done.fromVersion()).isEqualTo("usb-4.0.0");
        assertThat(done.log()).contains("Writing at 0x00050000... (100 %)");
        var order = inOrder(ports);
        order.verify(ports).release("COM3");
        order.verify(ports).reclaim("COM3");
    }

    @Test
    void aFailedFlashStillGivesThePortBack() throws Exception {
        flasher = (port, image, output) -> {
            output.accept("A fatal error occurred: Failed to connect to ESP32");
            return 2;
        };
        service.start(DEVICE, WASHER_IMAGE, null);

        FirmwareUpdateStatus failed = awaitFinished();

        assertThat(failed.state()).isEqualTo(State.FAILED);
        assertThat(failed.message()).contains("Failed to connect");
        var order = inOrder(ports);
        order.verify(ports).release("COM3");
        order.verify(ports).reclaim("COM3");
        // The next attempt is allowed.
        flasher = (port, image, output) -> { rebooted.set(true); device.setFirmwareVersion("usb-4.1.0"); return 0; };
        service.start(DEVICE, WASHER_IMAGE, null);
        assertThat(awaitFinished().state()).isEqualTo(State.DONE);
    }

    @Test
    void refusesFirmwareForAnotherMachineType() {
        byte[] dryer = FirmwareImageTest.image("LAUNDRYOS-FW|DRYER|dryer-usb-2.1.0", 4096);

        assertThatThrownBy(() -> service.start(DEVICE, dryer, null))
                .isInstanceOf(ProvisioningRejectedException.class)
                .hasMessageContaining("DRYER firmware");
        assertThat(service.status(DEVICE)).isEmpty();
    }

    @Test
    void refusesWhileACustomerUsesTheMachine() {
        machine.setStatus(MachineStatus.RUNNING);

        assertThatThrownBy(() -> service.start(DEVICE, WASHER_IMAGE, null))
                .isInstanceOf(ProvisioningRejectedException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    void refusesABoardThatIsNotPluggedIn() {
        when(link.portOf(DEVICE)).thenReturn(null);

        assertThatThrownBy(() -> service.start(DEVICE, WASHER_IMAGE, null))
                .hasMessageContaining("not plugged");
    }
}
