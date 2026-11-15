package com.laundry.iot;

import com.laundry.devicelink.DeviceCredentials;
import com.laundry.devicelink.DeviceIdentity;
import com.laundry.devicelink.DeviceKeys;
import com.laundry.devicelink.LinkState;
import com.laundry.devicelink.TestDeviceKey;
import com.laundry.devicelink.mqtt.MqttLinkProperties;
import com.laundry.iot.dto.ProvisioningDtos.RegisterRequest;
import com.laundry.iot.dto.ProvisioningDtos.RegisterResponse;
import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import com.laundry.iot.service.DeviceRegistrationService;
import com.laundry.iot.service.DeviceRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A board gets its own MQTT account over HTTP by signing a single-use nonce with its key. */
class DeviceRegistrationServiceTest {

    private static final String DEVICE = "ESP-24A1B2C3D4E5";
    private static final String IP = "192.168.1.23";

    private final DeviceRegistryService registry = mock(DeviceRegistryService.class);
    private final IotDeviceRepository repository = mock(IotDeviceRepository.class);
    private final DeviceCredentials accounts = mock(DeviceCredentials.class);
    private final TestDeviceKey key = new TestDeviceKey();
    private DeviceRegistrationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<DeviceCredentials> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(accounts);
        MqttLinkProperties mqtt = new MqttLinkProperties();
        when(repository.findByDeviceId(anyString())).thenReturn(Optional.empty());
        when(accounts.issue(DEVICE)).thenReturn(Optional.of("own-password"));
        service = new DeviceRegistrationService(registry, repository, provider, mqtt, java.time.Clock.systemUTC());
    }

    private RegisterRequest signed(TestDeviceKey signer, String nonce) {
        return new RegisterRequest(DEVICE, "wifi-6.0.0", "WASHER", key.publicKeyBase64(), nonce,
                signer.sign(DeviceKeys.registerMessage(DEVICE, nonce)), -60);
    }

    @Test
    void aSignedRegistrationGetsItsOwnAccountSealedToItsKey() {
        when(registry.onHello(eq(DEVICE), any(), any(), any(DeviceIdentity.class)))
                .thenReturn(LinkState.of(LinkState.State.PENDING, DEVICE));
        String nonce = service.challenge(DEVICE, IP).nonce();

        RegisterResponse account = service.register(signed(key, nonce), IP).account().orElseThrow();

        assertThat(account.username()).isEqualTo(DEVICE);
        assertThat(account.state()).isEqualTo("PENDING");
        assertThat(account.mqttPort()).isEqualTo(8883);
        assertThat(key.open(DEVICE, account.epk(), account.iv(), account.ct())).isEqualTo("own-password");
    }

    @Test
    void aWrongSignatureOrAReusedNonceIsRefused() {
        String nonce = service.challenge(DEVICE, IP).nonce();
        assertThatThrownBy(() -> service.register(signed(new TestDeviceKey(), nonce), IP))
                .isInstanceOf(ProvisioningRejectedException.class)
                .satisfies(e -> assertThat(((ProvisioningRejectedException) e).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        // The nonce was spent by the failed attempt: replaying it, even correctly signed, is refused.
        assertThatThrownBy(() -> service.register(signed(key, nonce), IP)).isInstanceOf(ProvisioningRejectedException.class);
        verify(accounts, never()).issue(anyString());
        verify(registry, never()).onHello(anyString(), any(), any(), any(DeviceIdentity.class));
    }

    @Test
    void aRevokedBoardOrAnotherKeyGetsNoAccount() {
        when(registry.onHello(eq(DEVICE), any(), any(), any(DeviceIdentity.class)))
                .thenReturn(LinkState.of(LinkState.State.REVOKED, DEVICE))
                .thenReturn(LinkState.of(LinkState.State.AUTH_FAILED, DEVICE));

        var revoked = service.register(signed(key, service.challenge(DEVICE, IP).nonce()), IP);
        var otherKey = service.register(signed(key, service.challenge(DEVICE, IP).nonce()), IP);

        assertThat(revoked.account()).isEmpty();
        assertThat(revoked.refusedState()).isEqualTo(LinkState.State.REVOKED);
        assertThat(otherKey.refusedState()).isEqualTo(LinkState.State.AUTH_FAILED);
        verify(accounts, never()).issue(anyString());
    }

    @Test
    void tooManyBoardsWaitingForApprovalStopsNewOnes() {
        when(repository.countByStatus(IotDeviceStatus.UNCLAIMED)).thenReturn(50L);
        String nonce = service.challenge(DEVICE, IP).nonce();
        assertThatThrownBy(() -> service.register(signed(key, nonce), IP))
                .satisfies(e -> assertThat(((ProvisioningRejectedException) e).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void oneAddressIsRateLimited() {
        for (int i = 0; i < 30; i++) service.challenge(DEVICE, "10.0.0.9");
        assertThatThrownBy(() -> service.challenge(DEVICE, "10.0.0.9"))
                .satisfies(e -> assertThat(((ProvisioningRejectedException) e).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        service.challenge(DEVICE, "10.0.0.10");   // another address is not affected
    }
}
