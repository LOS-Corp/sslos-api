package com.laundry.devicelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.iot.service.DeviceRegistryService;
import com.laundry.machine.entity.Machine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The device link core, driven with fake MQTT sessions: a HELLO counts only after the board signed a
 * fresh nonce, and only a bound board's frames are routed.
 */
class DeviceLinkHubTest {

    private static final String DEVICE = "ESP-24A1B2C3D4E5";
    private static final Pattern NONCE = Pattern.compile("\"nonce\":\"([^\"]+)\"");

    private final DeviceRegistryService registry = mock(DeviceRegistryService.class);
    private final DeviceMessageRouter router = mock(DeviceMessageRouter.class);
    private final DeviceCredentials accounts = mock(DeviceCredentials.class);
    private final TestDeviceKey key = new TestDeviceKey();
    private DeviceLinkHub hub;

    /** One board's session; what the backend published is kept as {@code "<channel> <json>"}. */
    static final class FakeBoard implements DeviceConnection {
        final String deviceId;
        final List<String> written = new ArrayList<>();

        FakeBoard(String deviceId) {
            this.deviceId = deviceId;
        }

        @Override public String deviceId() { return deviceId; }
        @Override public void send(String channel, String json) { written.add(channel + " " + json); }

        String last() { return written.get(written.size() - 1); }

        String nonce() {
            for (int i = written.size() - 1; i >= 0; i--) {
                if (written.get(i).startsWith("challenge ")) {
                    Matcher m = NONCE.matcher(written.get(i));
                    if (m.find()) return m.group(1);
                }
            }
            throw new AssertionError("no challenge in " + written);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<DeviceRegistryService> registryProvider = mock(ObjectProvider.class);
        when(registryProvider.getObject()).thenReturn(registry);
        ObjectProvider<DeviceMessageRouter> routerProvider = mock(ObjectProvider.class);
        when(routerProvider.getObject()).thenReturn(router);
        ObjectProvider<DeviceCredentials> accountsProvider = mock(ObjectProvider.class);
        when(accountsProvider.getIfAvailable()).thenReturn(accounts);
        hub = new DeviceLinkHub(registryProvider, routerProvider, accountsProvider, new ObjectMapper());
    }

    /** The board publishes {@code json} on {@code up/<channel>}; written as {@code "@<channel> <json>"} for brevity. */
    private void receive(DeviceConnection board, String frame) {
        int space = frame.indexOf(' ');
        hub.frameReceived(board, frame.substring(1, space), frame.substring(space + 1));
    }

    private String hello(String deviceId, String publicKey) {
        return "@hello {\"deviceId\":\"" + deviceId + "\",\"firmwareVersion\":\"5.0.0\",\"deviceType\":\"WASHER\","
                + "\"publicKey\":\"" + publicKey + "\",\"ip\":\"192.168.1.23\",\"rssi\":-58}";
    }

    private String proof(String nonce, String signature) {
        return "@proof {\"nonce\":\"" + nonce + "\",\"signature\":\"" + signature + "\"}";
    }

    /** Full handshake; the registry answers {@code state}. */
    private FakeBoard connect(LinkState state) {
        FakeBoard board = new FakeBoard(DEVICE);
        when(registry.onHello(eq(DEVICE), any(), any(), any(DeviceIdentity.class))).thenReturn(state);
        hub.connected(board);
        receive(board, hello(DEVICE, key.publicKeyBase64()));
        String nonce = board.nonce();
        receive(board, proof(nonce, key.sign(DeviceKeys.challengeMessage(DEVICE, nonce))));
        return board;
    }

    // ── Routing and commands ───────────────────────────────────────────────

    @Test
    void asksForHelloAsSoonAsTheBoardIsOnline() {
        FakeBoard board = new FakeBoard(DEVICE);
        hub.connected(board);
        assertThat(board.last()).startsWith("link ").contains("HELLO_REQUIRED");
    }

    @Test
    void routesFramesOnlyFromABoundBoard() {
        FakeBoard board = connect(LinkState.of(LinkState.State.VERIFIED, DEVICE));

        receive(board, "@heartbeat {\"status\":\"AVAILABLE\"}");
        verify(router, never()).route(any(), any(), any());

        when(registry.linkState(DEVICE)).thenReturn(LinkState.bound(DEVICE, "WM001", "WASHER"));
        hub.onLinkChanged(new DeviceLinkChanged(DEVICE));
        assertThat(board.last()).contains("\"state\":\"BOUND\"").contains("WM001");

        receive(board, "@heartbeat {\"status\":\"AVAILABLE\"}");
        verify(router).route("WM001", DeviceChannel.HEARTBEAT, "{\"status\":\"AVAILABLE\"}");
        receive(board, "@command/ack {\"correlationId\":\"c1\"}");
        verify(router).route("WM001", DeviceChannel.COMMAND_ACK, "{\"correlationId\":\"c1\"}");
    }

    @Test
    void framesBeforeHelloAndNonJsonPayloadsAreNotRouted() {
        FakeBoard board = new FakeBoard(DEVICE);
        hub.connected(board);

        receive(board, "@status {\"status\":\"AVAILABLE\"}");
        assertThat(board.last()).contains("HELLO_REQUIRED");

        FakeBoard bound = connect(LinkState.bound(DEVICE, "WM001", "WASHER"));
        receive(bound, "@status not-json");
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    void aSecondLiveSessionForTheSameBoardIsRefused() {
        connect(LinkState.bound(DEVICE, "WM001", "WASHER"));
        FakeBoard second = new FakeBoard(DEVICE);
        hub.connected(second);

        receive(second, hello(DEVICE, key.publicKeyBase64()));
        String nonce = second.nonce();
        receive(second, proof(nonce, key.sign(DeviceKeys.challengeMessage(DEVICE, nonce))));

        assertThat(second.last()).contains("DUPLICATE");
        receive(second, "@status {\"status\":\"RUNNING\"}");
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    void commandsGoToTheBoundBoardsCommandTopic() {
        FakeBoard board = connect(LinkState.bound(DEVICE, "WM001", "WASHER"));
        Machine machine = Machine.builder().machineCode("WM001").deviceId(DEVICE).build();

        hub.send(machine, "{\"type\":\"STOP\"}");

        assertThat(board.last()).isEqualTo("command {\"type\":\"STOP\"}");
    }

    @Test
    void sendingToABoardThatLeftFailsAndLeavingMarksItOffline() {
        FakeBoard board = connect(LinkState.bound(DEVICE, "WM001", "WASHER"));
        Machine machine = Machine.builder().machineCode("WM001").deviceId(DEVICE).build();

        hub.disconnected(board);

        verify(registry).onDisconnected(DEVICE);
        assertThat(hub.isConnected(DEVICE)).isFalse();
        assertThatThrownBy(() -> hub.send(machine, "{}")).isInstanceOf(DeviceNotConnectedException.class);
        Machine unbound = Machine.builder().machineCode("WM002").build();
        assertThatThrownBy(() -> hub.send(unbound, "{}")).isInstanceOf(DeviceNotConnectedException.class);
    }

    // ── Authentication ─────────────────────────────────────────────────────

    @Test
    void helloIsAcceptedOnlyAfterAValidSignature() {
        FakeBoard board = new FakeBoard(DEVICE);
        when(registry.onHello(eq(DEVICE), eq("5.0.0"), eq("WASHER"), any(DeviceIdentity.class)))
                .thenReturn(LinkState.of(LinkState.State.PENDING, DEVICE));
        hub.connected(board);

        receive(board, hello(DEVICE, key.publicKeyBase64()));
        assertThat(board.last()).startsWith("challenge ");
        assertThat(hub.isConnected(DEVICE)).isFalse();
        verify(registry, never()).onHello(anyString(), any(), any(), any(DeviceIdentity.class));

        String nonce = board.nonce();
        receive(board, proof(nonce, key.sign(DeviceKeys.challengeMessage(DEVICE, nonce))));

        assertThat(board.last()).contains("\"state\":\"PENDING\"");
        assertThat(hub.isConnected(DEVICE)).isTrue();
        verify(registry).onHello(eq(DEVICE), eq("5.0.0"), eq("WASHER"), any(DeviceIdentity.class));
    }

    @Test
    void aWrongSignatureIsRefusedWithoutTouchingTheRegistry() {
        FakeBoard board = new FakeBoard(DEVICE);
        TestDeviceKey thief = new TestDeviceKey();
        hub.connected(board);

        receive(board, hello(DEVICE, key.publicKeyBase64()));
        String nonce = board.nonce();
        receive(board, proof(nonce, thief.sign(DeviceKeys.challengeMessage(DEVICE, nonce))));

        assertThat(board.last()).contains("AUTH_FAILED");
        assertThat(hub.isConnected(DEVICE)).isFalse();
        verify(registry, never()).onHello(anyString(), any(), any(), any(DeviceIdentity.class));

        // Frames after a refusal are dropped, and an immediate new HELLO is not even answered.
        receive(board, "@status {\"status\":\"RUNNING\"}");
        int before = board.written.size();
        receive(board, hello(DEVICE, key.publicKeyBase64()));
        assertThat(board.written).hasSize(before);
        verify(router, never()).route(any(), any(), any());
    }

    @Test
    void aBoardCannotSpeakForAnotherClientId() {
        FakeBoard board = new FakeBoard("ESP-000000000001");
        hub.connected(board);

        receive(board, hello(DEVICE, key.publicKeyBase64()));

        assertThat(board.last()).contains("AUTH_FAILED");
        assertThat(hub.isConnected(DEVICE)).isFalse();
    }

    @Test
    void anAnswerToAnOlderChallengeIsIgnored() {
        FakeBoard board = new FakeBoard(DEVICE);
        when(registry.onHello(eq(DEVICE), any(), any(), any(DeviceIdentity.class)))
                .thenReturn(LinkState.of(LinkState.State.VERIFIED, DEVICE));
        hub.connected(board);
        receive(board, hello(DEVICE, key.publicKeyBase64()));
        String first = board.nonce();
        receive(board, hello(DEVICE, key.publicKeyBase64()));
        String second = board.nonce();

        receive(board, proof(first, key.sign(DeviceKeys.challengeMessage(DEVICE, first))));
        assertThat(hub.isConnected(DEVICE)).isFalse();

        receive(board, proof(second, key.sign(DeviceKeys.challengeMessage(DEVICE, second))));
        assertThat(hub.isConnected(DEVICE)).isTrue();
    }

    @Test
    void anUntrustedKeyStaysUnboundUntilTheAdminTrustsIt() {
        FakeBoard board = new FakeBoard(DEVICE);
        when(registry.onHello(eq(DEVICE), any(), any(), any(DeviceIdentity.class)))
                .thenReturn(LinkState.of(LinkState.State.AUTH_FAILED, DEVICE));
        hub.connected(board);
        receive(board, hello(DEVICE, key.publicKeyBase64()));
        String nonce = board.nonce();
        receive(board, proof(nonce, key.sign(DeviceKeys.challengeMessage(DEVICE, nonce))));

        assertThat(board.last()).contains("AUTH_FAILED");
        assertThat(hub.isConnected(DEVICE)).isFalse();

        hub.onLinkChanged(new DeviceLinkChanged(DEVICE));   // admin trusted the key
        assertThat(board.last()).contains("HELLO_REQUIRED");
        receive(board, hello(DEVICE, key.publicKeyBase64()));
        assertThat(board.last()).startsWith("challenge ");
    }

    @Test
    void helloWithoutAKeyIsRefused() {
        FakeBoard board = new FakeBoard(DEVICE);
        hub.connected(board);

        receive(board, "@hello {\"deviceId\":\"" + DEVICE + "\",\"firmwareVersion\":\"5.0.0\",\"deviceType\":\"WASHER\"}");

        assertThat(board.last()).contains("AUTH_FAILED");
    }

    @Test
    void sendFrameReachesAConnectedBoardOnly() {
        FakeBoard board = new FakeBoard(DEVICE);
        when(registry.onHello(eq(DEVICE), any(), any(), any(DeviceIdentity.class)))
                .thenReturn(LinkState.bound(DEVICE, "WM001", "WASHER"));
        hub.connected(board);
        receive(board, hello(DEVICE, key.publicKeyBase64()));
        String nonce = board.nonce();
        receive(board, proof(nonce, key.sign(DeviceKeys.challengeMessage(DEVICE, nonce))));

        hub.sendFrame(DEVICE, DeviceFrame.NETWORK, "{\"action\":\"RESET\"}");
        assertThat(board.last()).isEqualTo("netcfg {\"action\":\"RESET\"}");

        hub.disconnected(board);
        assertThatThrownBy(() -> hub.sendFrame(DEVICE, DeviceFrame.NETWORK, "{}"))
                .isInstanceOf(DeviceNotConnectedException.class);
    }

    @Test
    void aHelloOnAnAuthenticatedSessionOnlyRefreshesTheState() {
        FakeBoard board = connect(LinkState.bound(DEVICE, "WM001", "WASHER"));
        when(registry.linkState(DEVICE)).thenReturn(LinkState.bound(DEVICE, "WM001", "WASHER"));
        TestDeviceKey clone = new TestDeviceKey();
        int challenges = (int) board.written.stream().filter(w -> w.startsWith("challenge ")).count();

        // A second HELLO on a bound session: no new handshake, the binding stays.
        receive(board, hello(DEVICE, clone.publicKeyBase64()));

        assertThat(board.written.stream().filter(w -> w.startsWith("challenge ")).count()).isEqualTo(challenges);
        assertThat(board.last()).startsWith("link ").contains("BOUND");
        assertThat(hub.isConnected(DEVICE)).isTrue();
        verify(registry, times(1)).onHello(anyString(), any(), any(), any(DeviceIdentity.class));
    }

    // ── Broker accounts ────────────────────────────────────────────────────

    @Test
    void revokingABoardRemovesItsBrokerAccount() {
        when(registry.linkState(DEVICE)).thenReturn(LinkState.of(LinkState.State.REVOKED, DEVICE));
        hub.onLinkChanged(new DeviceLinkChanged(DEVICE));
        verify(accounts).revoke(DEVICE);
    }
}
