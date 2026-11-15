package com.laundry.iot.service;

import com.laundry.devicelink.DeviceCredentials;
import com.laundry.devicelink.DeviceIdentity;
import com.laundry.devicelink.DeviceIds;
import com.laundry.devicelink.DeviceKeys;
import com.laundry.devicelink.LinkState;
import com.laundry.devicelink.mqtt.MqttLinkProperties;
import com.laundry.iot.dto.ProvisioningDtos.RegisterChallenge;
import com.laundry.iot.dto.ProvisioningDtos.RegisterRequest;
import com.laundry.iot.dto.ProvisioningDtos.RegisterResponse;
import com.laundry.iot.entity.IotDeviceStatus;
import com.laundry.iot.exception.ProvisioningRejectedException;
import com.laundry.iot.repository.IotDeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A board gets its own broker account over HTTP, the way it used to with EMQX Cloud: on the store LAN it asks for a
 * nonce, signs it with its own key and receives its MQTT password sealed to that key. No shared MQTT password exists.
 *
 * <ol>
 *   <li>{@link #challenge}: a single-use nonce for the deviceId (one minute);</li>
 *   <li>{@link #register}: the signature proves the key; the registry pins it the first time (the board appears as
 *       "Chờ duyệt") or checks it against the pinned one; the board then gets (or re-gets) its broker user.</li>
 * </ol>
 * Anyone on the LAN can call this, so: rate limits per address, a cap on boards waiting for approval, and an
 * unapproved board's account is useless anyway (its own topics only, and the backend drops its frames until an
 * admin approves it after comparing the key fingerprint on the board's screen). Revoked boards get nothing.
 */
@Service
@Slf4j
public class DeviceRegistrationService {

    static final long NONCE_TTL_MS = 60_000;
    /** Requests per client address per minute (challenge + register); boards behind one NAT share it. */
    static final int MAX_REQUESTS_PER_MINUTE = 30;
    /** New boards waiting for approval at the same time; more are refused until an admin reviews them. */
    static final int MAX_PENDING_BOARDS = 50;
    /** Outstanding nonces; a registration storm beyond this is refused instead of growing the map. */
    static final int MAX_OPEN_CHALLENGES = 1_000;
    /** Client addresses tracked by the rate limiter before the table is reset (bounded memory). */
    static final int MAX_TRACKED_ADDRESSES = 10_000;
    private static final long RATE_WINDOW_MS = 60_000;

    private final DeviceRegistryService registry;
    private final IotDeviceRepository deviceRepository;
    private final ObjectProvider<DeviceCredentials> credentials;
    private final MqttLinkProperties mqtt;
    private final Clock clock;

    private record Pending(String nonce, long expiresAt) {
    }

    private final Map<String, Pending> nonces = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> requests = new ConcurrentHashMap<>();

    public DeviceRegistrationService(DeviceRegistryService registry, IotDeviceRepository deviceRepository,
                                     ObjectProvider<DeviceCredentials> credentials, MqttLinkProperties mqtt,
                                     Clock clock) {
        this.registry = registry;
        this.deviceRepository = deviceRepository;
        this.credentials = credentials;
        this.mqtt = mqtt;
        this.clock = clock;
    }

    public RegisterChallenge challenge(String rawDeviceId, String clientAddress) {
        throttle(clientAddress);
        String deviceId = DeviceIds.normalize(rawDeviceId);
        long now = clock.millis();
        nonces.values().removeIf(p -> p.expiresAt() < now);
        if (nonces.size() >= MAX_OPEN_CHALLENGES) {
            throw new ProvisioningRejectedException(HttpStatus.TOO_MANY_REQUESTS, "Too many registrations at once");
        }
        String nonce = DeviceKeys.newNonce();
        nonces.put(deviceId, new Pending(nonce, now + NONCE_TTL_MS));
        return new RegisterChallenge(nonce, (int) (NONCE_TTL_MS / 1000));
    }

    /**
     * @return the sealed account, or the refusal when the board may not have one (another key pinned, or revoked)
     */
    public Result register(RegisterRequest request, String clientAddress) {
        throttle(clientAddress);
        String deviceId = DeviceIds.normalize(request.deviceId());
        Pending pending = nonces.remove(deviceId);   // single use, even when the signature is wrong
        if (pending == null || pending.expiresAt() < clock.millis() || !pending.nonce().equals(request.nonce())) {
            throw new ProvisioningRejectedException(HttpStatus.UNAUTHORIZED,
                    "Unknown or expired nonce; ask for a new challenge");
        }
        byte[] key = DeviceKeys.decodePublicKey(request.publicKey())
                .orElseThrow(() -> new ProvisioningRejectedException(HttpStatus.BAD_REQUEST,
                        "publicKey is not a P-256 point"));
        if (!DeviceKeys.verify(key, DeviceKeys.registerMessage(deviceId, pending.nonce()), request.signature())) {
            log.warn("[IOT] Registration of {} from {}: bad signature", deviceId, clientAddress);
            throw new ProvisioningRejectedException(HttpStatus.UNAUTHORIZED, "Signature does not match the public key");
        }
        if (deviceRepository.findByDeviceId(deviceId).isEmpty()
                && deviceRepository.countByStatus(IotDeviceStatus.UNCLAIMED) >= MAX_PENDING_BOARDS) {
            throw new ProvisioningRejectedException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many boards are waiting for approval; approve or reject them first");
        }
        LinkState link = registry.onHello(deviceId, request.firmwareVersion(), request.deviceType(),
                new DeviceIdentity(key, clientAddress, request.rssi()));
        if (link.state() == LinkState.State.AUTH_FAILED || link.state() == LinkState.State.REVOKED) {
            log.warn("[IOT] Registration of {} refused: {}", deviceId, link.state());
            return Result.refused(link.state());
        }
        DeviceCredentials accounts = credentials.getIfAvailable();
        if (accounts == null) {
            throw new ProvisioningRejectedException(HttpStatus.SERVICE_UNAVAILABLE, "The MQTT link is off");
        }
        String password = accounts.issue(deviceId).orElseThrow(() -> new ProvisioningRejectedException(
                HttpStatus.SERVICE_UNAVAILABLE, "The MQTT broker did not accept the account"));
        DeviceKeys.Sealed sealed = DeviceKeys.seal(key, deviceId, password);
        log.info("[IOT] {} registered from {} ({}): broker account issued", deviceId, clientAddress, link.state());
        return Result.ok(new RegisterResponse(link.state().name(), deviceId, mqtt.getDevicePort(),
                sealed.epk(), sealed.iv(), sealed.ct()));
    }

    /** Either the sealed account or, with {@code account} empty, the state the board was refused in. */
    public record Result(Optional<RegisterResponse> account, LinkState.State refusedState) {
        static Result ok(RegisterResponse response) {
            return new Result(Optional.of(response), null);
        }

        static Result refused(LinkState.State state) {
            return new Result(Optional.empty(), state);
        }

        /** What the admin has to do; shown in the board's log. */
        public String refusalMessage() {
            return refusedState == LinkState.State.AUTH_FAILED
                    ? "Another key is pinned for this board; an admin must trust the new key first"
                    : "This board is revoked; an admin must approve it again";
        }
    }

    /** At most {@link #MAX_REQUESTS_PER_MINUTE} per client address. */
    private void throttle(String clientAddress) {
        long now = clock.millis();
        Deque<Long> times = requests.computeIfAbsent(clientAddress == null ? "?" : clientAddress,
                address -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > RATE_WINDOW_MS) times.pollFirst();
            if (times.size() >= MAX_REQUESTS_PER_MINUTE) {
                throw new ProvisioningRejectedException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests; wait a minute");
            }
            times.addLast(now);
        }
        if (requests.size() > MAX_TRACKED_ADDRESSES) requests.clear();
    }
}
