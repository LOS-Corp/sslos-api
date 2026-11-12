package com.laundry.iot;

import com.laundry.devicelink.DeviceLink;
import com.laundry.devicelink.DeviceLinkChanged;
import com.laundry.devicelink.LinkState;
import com.laundry.iot.dto.ProvisioningDtos.PairDeviceRequest;
import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.iot.service.DeviceRegistryService;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineDeploymentStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.DeviceEventRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineService;
import com.laundry.machine.service.MachineWebSocketBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;
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
 * USB onboarding: HELLO creates an UNCLAIMED board, an admin approves it, the
 * owner attaches it to a machine. Repositories are in-memory fakes so the state
 * transitions are observable.
 */
class DeviceRegistryServiceTest {

    private static final String DEVICE = "ESP-24A1B2C3D4E5";
    private static final UUID ADMIN = UUID.randomUUID();

    private final Map<String, IotDevice> devices = new HashMap<>();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final MachineService machineService = mock(MachineService.class);
    private Machine machine;
    private MachineRepository machineRepository;
    private IotDeviceRepository deviceRepository;
    private DeviceRegistryService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(IotDeviceRepository.class);
        when(deviceRepository.findByDeviceIdForUpdate(anyString()))
                .thenAnswer(call -> Optional.ofNullable(devices.get(call.<String>getArgument(0))));
        when(deviceRepository.findByDeviceId(anyString()))
                .thenAnswer(call -> Optional.ofNullable(devices.get(call.<String>getArgument(0))));
        when(deviceRepository.save(any(IotDevice.class))).thenAnswer(call -> {
            IotDevice device = call.getArgument(0);
            devices.put(device.getDeviceId(), device);
            return device;
        });

        machine = Machine.builder().id(UUID.randomUUID()).machineCode("WM01").branchId(UUID.randomUUID())
                .type(MachineType.WASHER).deploymentStatus(MachineDeploymentStatus.UNASSIGNED).build();
        machineRepository = mock(MachineRepository.class);
        when(machineRepository.findById(machine.getId())).thenReturn(Optional.of(machine));
        when(machineRepository.findByMachineCode("WM01")).thenReturn(Optional.of(machine));
        when(machineRepository.save(any(Machine.class))).thenAnswer(call -> call.getArgument(0));

        service = new DeviceRegistryService(deviceRepository, machineRepository, mock(DeviceEventRepository.class),
                mock(MachineWebSocketBroadcaster.class), machineService, mock(DeviceLink.class), events);
    }

    @Test
    void firstHelloCreatesAnUnclaimedBoardWaitingForApproval() {
        LinkState link = service.onHello(DEVICE, "2.0", "washer");

        assertThat(link.state()).isEqualTo(LinkState.State.PENDING);
        IotDevice device = devices.get(DEVICE);
        assertThat(device.getStatus()).isEqualTo(IotDeviceStatus.UNCLAIMED);
        assertThat(device.getDeviceType()).isEqualTo(MachineType.WASHER);
        assertThat(device.getFirmwareVersion()).isEqualTo("2.0");
        assertThat(device.getLastSeenAt()).isNotNull();
    }

    @Test
    void approvedBoardIsAttachedAndThenReportsItsMachine() {
        service.onHello(DEVICE, "2.0", "WASHER");
        service.approve(DEVICE, ADMIN);
        assertThat(devices.get(DEVICE).getStatus()).isEqualTo(IotDeviceStatus.VERIFIED);
        assertThat(devices.get(DEVICE).getVerifiedBy()).isEqualTo(ADMIN);
        assertThat(service.linkState(DEVICE).state()).isEqualTo(LinkState.State.VERIFIED);

        service.startPairing("WM01", new PairDeviceRequest(DEVICE), ADMIN);

        assertThat(devices.get(DEVICE).getStatus()).isEqualTo(IotDeviceStatus.PROVISIONED);
        assertThat(machine.getDeploymentStatus()).isEqualTo(MachineDeploymentStatus.PROVISIONED);
        assertThat(machine.getDeviceId()).isEqualTo(DEVICE);
        LinkState link = service.linkState(DEVICE);
        assertThat(link.isBound()).isTrue();
        assertThat(link.machineCode()).isEqualTo("WM01");
        verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(new DeviceLinkChanged(DEVICE));
    }

    @Test
    void theBoardGetsItsQrUrlOnlyOnceTheMachineIsActive() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "qrBaseUrl", "http://192.168.1.12:5173/");
        service.onHello(DEVICE, "2.0", "WASHER");
        service.approve(DEVICE, ADMIN);
        service.startPairing("WM01", new PairDeviceRequest(DEVICE), ADMIN);
        assertThat(service.linkState(DEVICE).qrUrl()).isNull();   // not sold to customers yet

        machine.setDeploymentStatus(MachineDeploymentStatus.ACTIVE);
        assertThat(service.linkState(DEVICE).qrUrl()).isEqualTo("http://192.168.1.12:5173/machines/WM01");

        machine.setDeploymentStatus(MachineDeploymentStatus.MAINTENANCE);
        assertThat(service.linkState(DEVICE).qrUrl()).isNull();
    }

    @Test
    void anUnapprovedOrUnknownBoardCannotBeAttached() {
        assertThatThrownBy(() -> service.startPairing("WM01", new PairDeviceRequest(DEVICE), ADMIN))
                .isInstanceOf(ProvisioningRejectedException.class);   // never seen on USB

        service.onHello(DEVICE, "2.0", "WASHER");
        assertThatThrownBy(() -> service.startPairing("WM01", new PairDeviceRequest(DEVICE), ADMIN))
                .isInstanceOf(ProvisioningRejectedException.class)
                .hasMessageContaining("not been approved");
        assertThat(machine.getDeploymentStatus()).isEqualTo(MachineDeploymentStatus.UNASSIGNED);
    }

    @Test
    void firmwareOfAnotherKindIsRefused() {
        service.onHello(DEVICE, "2.0", "LOCKER");
        service.approve(DEVICE, ADMIN);

        assertThatThrownBy(() -> service.startPairing("WM01", new PairDeviceRequest(DEVICE), ADMIN))
                .isInstanceOf(ProvisioningRejectedException.class)
                .hasMessageContaining("LOCKER");
        assertThat(devices.get(DEVICE).getStatus()).isEqualTo(IotDeviceStatus.VERIFIED);
    }

    @Test
    void unpairRevokesTheBoardUntilAnAdminApprovesItAgain() {
        service.onHello(DEVICE, "2.0", "WASHER");
        service.approve(DEVICE, ADMIN);
        service.startPairing("WM01", new PairDeviceRequest(DEVICE), ADMIN);
        when(machineRepository.hasActiveBookingAllocation(machine.getId())).thenReturn(false);

        service.unpair("WM01");

        assertThat(devices.get(DEVICE).getStatus()).isEqualTo(IotDeviceStatus.REVOKED);
        assertThat(devices.get(DEVICE).getMachineId()).isNull();
        assertThat(machine.getDeploymentStatus()).isEqualTo(MachineDeploymentStatus.UNASSIGNED);
        assertThat(service.linkState(DEVICE).state()).isEqualTo(LinkState.State.REVOKED);

        service.approve(DEVICE, ADMIN);
        assertThat(devices.get(DEVICE).getStatus()).isEqualTo(IotDeviceStatus.VERIFIED);
    }

    @Test
    void ownerSeesOnlyApprovedUnattachedBoardsOfTheRightKind() {
        when(deviceRepository.findAllByOrderByEnrolledAtDesc()).thenAnswer(call -> devices.values().stream().toList());
        service.onHello(DEVICE, "2.0", "WASHER");
        service.onHello("ESP-000000000002", "2.0", "DRYER");
        assertThat(service.availableDevices(null)).isEmpty();   // nothing approved yet

        service.approve(DEVICE, ADMIN);
        service.approve("ESP-000000000002", ADMIN);

        assertThat(service.availableDevices(MachineType.WASHER)).extracting("deviceId").containsExactly(DEVICE);
        service.startPairing("WM01", new PairDeviceRequest(DEVICE), ADMIN);
        assertThat(service.availableDevices(null)).extracting("deviceId").containsExactly("ESP-000000000002");
    }

    @Test
    void unplugMarksTheBoundMachineOffline() {
        service.onHello(DEVICE, "2.0", "WASHER");
        service.approve(DEVICE, ADMIN);
        service.startPairing("WM01", new PairDeviceRequest(DEVICE), ADMIN);

        service.onDisconnected(DEVICE);

        verify(machineService).markOfflineFromDevice(machine.getBranchId(), "WM01", "OFFLINE", null);
    }
}
