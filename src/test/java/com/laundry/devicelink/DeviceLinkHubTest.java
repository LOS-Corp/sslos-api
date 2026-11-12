package com.laundry.devicelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.iot.service.DeviceRegistryService;
import com.laundry.machine.entity.Machine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The serial link core, driven with fake connections instead of real ports. */
class DeviceLinkHubTest {

    private static final String DEVICE = "ESP-24A1B2C3D4E5";
    private static final UUID BRANCH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String HELLO = "@hello {\"deviceId\":\"" + DEVICE + "\",\"firmwareVersion\":\"2.0\",\"deviceType\":\"WASHER\"}";

    private final DeviceRegistryService registry = mock(DeviceRegistryService.class);
    private final DeviceMessageRouter router = mock(DeviceMessageRouter.class);
    private DeviceLinkHub hub;

    static final class FakeConnection implements DeviceConnection {
        final String name;
        final List<String> written = new ArrayList<>();

        FakeConnection(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void writeLine(String line) {
            written.add(line);
        }

        String last() {
            return written.get(written.size() - 1);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<DeviceRegistryService> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getObject()).thenReturn(registry);
        ObjectProvider<DeviceMessageRouter> routerProvider = mock(ObjectProvider.class);
        when(routerProvider.getObject()).thenReturn(router);
        hub = new DeviceLinkHub(registryProvider, routerProvider, new ObjectMapper());
    }

    @Test
    void asksForHelloThenAnswersWithTheBoardsState() {
        FakeConnection com3 = new FakeConnection("COM3");
        when(registry.onHello(DEVICE, "2.0", "WASHER")).thenReturn(LinkState.of(LinkState.State.PENDING, DEVICE));

        hub.connected(com3);
        assertThat(com3.last()).startsWith("@link ").contains("HELLO_REQUIRED");

        hub.lineReceived(com3, HELLO);
        assertThat(com3.last()).contains("\"state\":\"PENDING\"");
        assertThat(hub.isConnected(DEVICE)).isTrue();
        assertThat(hub.portOf(DEVICE)).isEqualTo("COM3");
    }

    @Test
    void routesFramesOnlyFromABoundBoard() {
        FakeConnection com3 = connect("COM3", LinkState.of(LinkState.State.VERIFIED, DEVICE));

        hub.lineReceived(com3, "@heartbeat {\"status\":\"AVAILABLE\"}");
        verify(router, never()).route(any(), any(), any(), any());

        when(registry.linkState(DEVICE)).thenReturn(LinkState.bound(DEVICE, BRANCH, "WM001", "WASHER"));
        hub.onLinkChanged(new DeviceLinkChanged(DEVICE));
        assertThat(com3.last()).contains("\"state\":\"BOUND\"").contains("WM001");

        hub.lineReceived(com3, "@heartbeat {\"status\":\"AVAILABLE\"}");
        verify(router).route(BRANCH, "WM001", DeviceChannel.HEARTBEAT, "{\"status\":\"AVAILABLE\"}");
    }

    @Test
    void logLinesAndFramesBeforeHelloAreNotRouted() {
        FakeConnection com3 = new FakeConnection("COM3");
        hub.connected(com3);

        hub.lineReceived(com3, "[BOOT] reset reason: POWERON");
        hub.lineReceived(com3, "@status {\"status\":\"AVAILABLE\"}");

        assertThat(com3.last()).contains("HELLO_REQUIRED");
        verify(router, never()).route(any(), any(), any(), any());
    }

    @Test
    void theSameBoardOnASecondPortIsRefused() {
        connect("COM3", LinkState.bound(DEVICE, BRANCH, "WM001", "WASHER"));
        FakeConnection com4 = new FakeConnection("COM4");
        hub.connected(com4);

        hub.lineReceived(com4, HELLO);

        assertThat(com4.last()).contains("DUPLICATE");
        assertThat(hub.portOf(DEVICE)).isEqualTo("COM3");
        hub.lineReceived(com4, "@status {\"status\":\"RUNNING\"}");
        verify(router, never()).route(any(), any(), any(), any());
    }

    @Test
    void commandsGoToTheBoundBoardsPort() {
        FakeConnection com3 = connect("COM3", LinkState.bound(DEVICE, BRANCH, "WM001", "WASHER"));
        Machine machine = Machine.builder().machineCode("WM001").branchId(BRANCH).deviceId(DEVICE).build();

        hub.send(machine, "{\"type\":\"STOP\"}");

        assertThat(com3.last()).isEqualTo("@cmd {\"type\":\"STOP\"}");
    }

    @Test
    void sendingToAnUnpluggedBoardFailsAndUnplugMarksItOffline() {
        FakeConnection com3 = connect("COM3", LinkState.bound(DEVICE, BRANCH, "WM001", "WASHER"));
        Machine machine = Machine.builder().machineCode("WM001").branchId(BRANCH).deviceId(DEVICE).build();

        hub.disconnected(com3);

        verify(registry).onDisconnected(DEVICE);
        assertThat(hub.isConnected(DEVICE)).isFalse();
        assertThatThrownBy(() -> hub.send(machine, "{}")).isInstanceOf(DeviceNotConnectedException.class);
        Machine unbound = Machine.builder().machineCode("WM002").branchId(BRANCH).build();
        assertThatThrownBy(() -> hub.send(unbound, "{}")).isInstanceOf(DeviceNotConnectedException.class);
    }

    private FakeConnection connect(String port, LinkState state) {
        FakeConnection connection = new FakeConnection(port);
        when(registry.onHello(DEVICE, "2.0", "WASHER")).thenReturn(state);
        hub.connected(connection);
        hub.lineReceived(connection, HELLO);
        return connection;
    }
}
