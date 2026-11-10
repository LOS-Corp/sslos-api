package com.laundry.iot;

import com.laundry.iot.config.IotProvisioningProperties;
import com.laundry.iot.dto.ProvisioningDtos.ActivateRequest;
import com.laundry.iot.dto.ProvisioningDtos.ActivateResponse;
import com.laundry.iot.dto.ProvisioningDtos.StartPairingRequest;
import com.laundry.iot.entity.IotDevice;
import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.iot.entity.PairingSession;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.iot.repository.PairingSessionRepository;
import com.laundry.iot.service.DeviceProvisioningService;
import com.laundry.iot.service.DeviceSecretService;
import com.laundry.iot.service.EmqxAdminClient;
import com.laundry.machine.entity.Machine;
import com.laundry.machine.entity.MachineDeploymentStatus;
import com.laundry.machine.entity.MachineType;
import com.laundry.machine.repository.DeviceEventRepository;
import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineWebSocketBroadcaster;
import com.laundry.mqtt.config.MqttProperties;
import com.laundry.mqtt.service.MqttTopicBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pairing by the setup key the board shows on its screen: the admin's scan
 * creates the device row, the board proves the key, wrong proofs abort the pairing.
 * Repositories are in-memory fakes so the state transitions are observable.
 */
class DeviceProvisioningServiceTest {

    private static final String DEVICE = "ESP-24A1B2C3D4E5";
    private static final String KEY = "123456";

    private final Map<String, IotDevice> devices = new HashMap<>();
    private final List<PairingSession> sessions = new ArrayList<>();
    private Machine machine;
    private EmqxAdminClient emqx;
    private MachineRepository machineRepository;
    private DeviceProvisioningService service;

    @BeforeEach
    void setUp() {
        IotProvisioningProperties properties = new IotProvisioningProperties();
        properties.setDeviceSecretKey("0123456789abcdef0123456789abcdef");
        DeviceSecretService secrets = new DeviceSecretService(properties);

        IotDeviceRepository deviceRepository = mock(IotDeviceRepository.class);
        when(deviceRepository.findByDeviceIdForUpdate(anyString()))
                .thenAnswer(call -> Optional.ofNullable(devices.get(call.<String>getArgument(0))));
        when(deviceRepository.save(any(IotDevice.class))).thenAnswer(call -> {
            IotDevice device = call.getArgument(0);
            devices.put(device.getDeviceId(), device);
            return device;
        });

        PairingSessionRepository sessionRepository = mock(PairingSessionRepository.class);
        when(sessionRepository.save(any(PairingSession.class))).thenAnswer(call -> {
            PairingSession session = call.getArgument(0);
            if (!sessions.contains(session)) sessions.add(session);
            return session;
        });
        when(sessionRepository.findByDeviceIdAndStatus(anyString(), any())).thenAnswer(call -> sessions.stream()
                .filter(s -> s.getDeviceId().equals(call.getArgument(0)) && s.getStatus() == call.getArgument(1)).toList());
        when(sessionRepository.findByMachineIdAndStatus(any(), any())).thenAnswer(call -> sessions.stream()
                .filter(s -> java.util.Objects.equals(s.getMachineId(), call.getArgument(0)) && s.getStatus() == call.getArgument(1)).toList());
        when(sessionRepository.findFirstByDeviceIdAndStatusOrderByCreatedAtDesc(anyString(), any())).thenAnswer(call -> sessions.stream()
                .filter(s -> s.getDeviceId().equals(call.getArgument(0)) && s.getStatus() == call.getArgument(1))
                .reduce((first, second) -> second));

        machine = Machine.builder().id(UUID.randomUUID()).machineCode("WM01").branchId(UUID.randomUUID())
                .type(MachineType.WASHER).deploymentStatus(MachineDeploymentStatus.UNASSIGNED).build();
        machineRepository = mock(MachineRepository.class);
        when(machineRepository.findById(machine.getId())).thenReturn(Optional.of(machine));
        when(machineRepository.findByMachineCode("WM01")).thenReturn(Optional.of(machine));
        when(machineRepository.save(any(Machine.class))).thenAnswer(call -> call.getArgument(0));

        emqx = mock(EmqxAdminClient.class);
        service = new DeviceProvisioningService(deviceRepository, sessionRepository, machineRepository,
                mock(DeviceEventRepository.class), secrets, emqx, new MqttTopicBuilder(), new MqttProperties(),
                properties, mock(MachineWebSocketBroadcaster.class));
    }

    @Test
    void firstScanCreatesTheDeviceAndKeepsTheKeyOnlyEncrypted() {
        service.startPairing("WM01", new StartPairingRequest(DEVICE, "123 456"), null);

        IotDevice device = devices.get(DEVICE);
        assertThat(device.getStatus()).isEqualTo(IotDeviceStatus.PAIRING);
        assertThat(device.getSetupKeyEnc()).isNotBlank().doesNotContain(KEY);
        assertThat(machine.getDeploymentStatus()).isEqualTo(MachineDeploymentStatus.PAIRING);
        assertThat(sessions).singleElement().satisfies(s -> assertThat(s.getStatus()).isEqualTo(PairingSession.Status.OPEN));
    }

    @Test
    void boardHoldingTheScannedKeyActivatesOnceAndTheKeyIsWiped() {
        service.startPairing("WM01", new StartPairingRequest(DEVICE, KEY), null);

        ActivateResponse response = activate(KEY);

        assertThat(response.machineCode()).isEqualTo("WM01");
        assertThat(response.username()).isEqualTo(DEVICE);
        IotDevice device = devices.get(DEVICE);
        assertThat(device.getStatus()).isEqualTo(IotDeviceStatus.PROVISIONED);
        assertThat(device.getSetupKeyEnc()).isNull();
        assertThat(machine.getDeploymentStatus()).isEqualTo(MachineDeploymentStatus.PROVISIONED);
        assertThat(machine.getDeviceId()).isEqualTo(DEVICE);
        verify(emqx).upsertUser(eq(DEVICE), anyString());
        // Single use: the same key cannot activate a second time.
        assertThatThrownBy(() -> service.challenge(DEVICE)).isInstanceOf(ProvisioningRejectedException.class);
    }

    @Test
    void wrongProofsAbortThePairingAtTheLimit() {
        service.startPairing("WM01", new StartPairingRequest(DEVICE, KEY), null);
        String typo = "123465";

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> activate(typo)).isInstanceOf(ProvisioningRejectedException.class);
        }
        assertThat(devices.get(DEVICE).getStatus()).isEqualTo(IotDeviceStatus.PAIRING);

        assertThatThrownBy(() -> activate(typo)).isInstanceOf(ProvisioningRejectedException.class);
        IotDevice device = devices.get(DEVICE);
        assertThat(device.getStatus()).isEqualTo(IotDeviceStatus.UNCLAIMED);
        assertThat(device.getSetupKeyEnc()).isNull();
        assertThat(machine.getDeploymentStatus()).isEqualTo(MachineDeploymentStatus.UNASSIGNED);
        assertThat(sessions).allSatisfy(s -> assertThat(s.getStatus()).isNotEqualTo(PairingSession.Status.OPEN));
        verify(emqx, never()).upsertUser(anyString(), anyString());
    }

    @Test
    void deviceFirstBoardIsVerifiedThenAttachedToAMachineWithoutANewScan() {
        service.registerDevice(new StartPairingRequest(DEVICE, KEY), null);
        assertThat(poll(KEY)).isEmpty();                                      // verified, no credential yet
        assertThat(devices.get(DEVICE).getStatus()).isEqualTo(IotDeviceStatus.VERIFIED);
        assertThat(devices.get(DEVICE).getVerifiedAt()).isNotNull();
        assertThat(service.challenge(DEVICE)).isEmpty();                      // waits for a machine (HTTP 202)
        verify(emqx, never()).upsertUser(anyString(), anyString());

        service.startPairing("WM01", new StartPairingRequest(DEVICE, null), null);   // picked from the list
        ActivateResponse response = activate(KEY);

        assertThat(response.machineCode()).isEqualTo("WM01");
        assertThat(devices.get(DEVICE).getStatus()).isEqualTo(IotDeviceStatus.PROVISIONED);
        assertThat(devices.get(DEVICE).getSetupKeyEnc()).isNull();
        assertThat(machine.getDeploymentStatus()).isEqualTo(MachineDeploymentStatus.PROVISIONED);
    }

    @Test
    void attachingWithoutACodeNeedsAVerifiedBoard() {
        assertThatThrownBy(() -> service.startPairing("WM01", new StartPairingRequest(DEVICE, null), null))
                .isInstanceOf(ProvisioningRejectedException.class);
        service.registerDevice(new StartPairingRequest(DEVICE, KEY), null);   // scanned but not proved yet
        assertThatThrownBy(() -> service.startPairing("WM01", new StartPairingRequest(DEVICE, ""), null))
                .isInstanceOf(ProvisioningRejectedException.class);
        assertThat(machine.getDeploymentStatus()).isEqualTo(MachineDeploymentStatus.UNASSIGNED);
    }

    @Test
    void unpairingAPendingMachineKeepsAVerifiedBoardVerified() {
        service.registerDevice(new StartPairingRequest(DEVICE, KEY), null);
        poll(KEY);
        service.startPairing("WM01", new StartPairingRequest(DEVICE, null), null);
        when(machineRepository.hasActiveBookingAllocation(machine.getId())).thenReturn(false);

        service.unpair("WM01");

        assertThat(devices.get(DEVICE).getStatus()).isEqualTo(IotDeviceStatus.VERIFIED);
        assertThat(devices.get(DEVICE).getSetupKeyEnc()).isNotNull();
        assertThat(machine.getDeploymentStatus()).isEqualTo(MachineDeploymentStatus.UNASSIGNED);
    }

    @Test
    void refusesAMalformedKeyAndABoardBoundElsewhere() {
        assertThatThrownBy(() -> service.startPairing("WM01", new StartPairingRequest(DEVICE, "12345"), null))
                .isInstanceOf(ProvisioningRejectedException.class);
        assertThat(devices).isEmpty();

        devices.put(DEVICE, IotDevice.builder().deviceId(DEVICE).status(IotDeviceStatus.ACTIVE).build());
        assertThatThrownBy(() -> service.startPairing("WM01", new StartPairingRequest(DEVICE, KEY), null))
                .isInstanceOf(ProvisioningRejectedException.class);
        assertThat(machine.getDeploymentStatus()).isEqualTo(MachineDeploymentStatus.UNASSIGNED);
    }

    /** What the firmware does: challenge, then HMAC(code, deviceId:nonce). Empty = HTTP 202 (verified only). */
    private Optional<ActivateResponse> poll(String keyOnBoard) {
        String nonce = service.challenge(DEVICE).orElseThrow().nonce();
        return service.activate(new ActivateRequest(DEVICE, nonce, hmacHex(keyOnBoard, DEVICE + ":" + nonce), "test-1.0"));
    }

    private ActivateResponse activate(String keyOnBoard) {
        return poll(keyOnBoard).orElseThrow();
    }

    private static String hmacHex(String key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
