package com.laundry.devicelink.mqtt;

import com.laundry.devicelink.DeviceCredentials;
import com.laundry.devicelink.DeviceIds;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;

/**
 * Accounts on the EMQX broker, through its REST API ({@code app.mqtt.api-url}, API key {@code app.mqtt.api-key}),
 * in EMQX's built-in user database. The same users are visible and editable in the EMQX Dashboard.
 *
 * <ul>
 *   <li>the backend's own user (superuser), created or re-keyed before the backend connects;</li>
 *   <li>{@link #issue}: an approved board gets its own user (username = deviceId, random password); the topic rights
 *       come from {@code emqx/acl.conf} ({@code ${username}} = its own subtree);</li>
 *   <li>{@link #revoke}: the user is deleted and the board disconnected, so the broker itself refuses it.</li>
 * </ul>
 * There is no shared board account: a board gets its own user from {@code POST /api/devices/register}.
 */
@Component
@ConditionalOnProperty(prefix = "app.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class BrokerAccounts implements DeviceCredentials {

    private static final String USERS = "/authentication/password_based:built_in_database/users";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int PASSWORD_BYTES = 24;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final MqttLinkProperties properties;
    private final RestClient api;

    public BrokerAccounts(MqttLinkProperties properties) {
        this.properties = properties;
        // JDK HttpClient: unlike HttpURLConnection it adds no form Content-Type to a body-less DELETE (EMQX rejects
        // that). HTTP/1.1: EMQX's API does not upgrade to h2c.
        JdkClientHttpRequestFactory http = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT).build());
        http.setReadTimeout(READ_TIMEOUT);
        this.api = RestClient.builder()
                .baseUrl(stripSlash(properties.getApiUrl()) + "/api/v5")
                .requestFactory(http)
                .defaultHeaders(h -> h.setBasicAuth(properties.getApiKey(), properties.getApiSecret()))
                .build();
    }

    // ── The backend's own user ─────────────────────────────────────────────

    /** Creates or re-keys the backend's broker user from the configuration; false when the API is unreachable. */
    public boolean ensureBackendUser() {
        boolean ok = upsertUser(properties.getUsername(), properties.getPassword(), true);
        if (!ok) log.warn("[MQTT] Cannot set up the backend's broker user through {}", properties.getApiUrl());
        return ok;
    }

    // ── Per-board users ────────────────────────────────────────────────────

    @Override
    public Optional<String> issue(String deviceId) {
        if (!DeviceIds.isValid(deviceId)) return Optional.empty();
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(PASSWORD_BYTES));
        if (!upsertUser(deviceId, password, false)) return Optional.empty();
        log.info("[MQTT] Own broker account issued for {}", deviceId);
        return Optional.of(password);
    }

    @Override
    public void revoke(String deviceId) {
        if (!DeviceIds.isValid(deviceId)) return;
        if (deleteUser(deviceId)) log.info("[MQTT] Broker account of {} deleted", deviceId);
        kickClient(deviceId);
    }

    // ── EMQX REST API ──────────────────────────────────────────────────────

    /** Changes the password of {@code user}, or creates it. */
    private boolean upsertUser(String user, String password, boolean superuser) {
        try {
            api.put().uri(USERS + "/{id}", user).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("password", password, "is_superuser", superuser))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException.NotFound missing) {
            try {
                api.post().uri(USERS).contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("user_id", user, "password", password, "is_superuser", superuser))
                        .retrieve().toBodilessEntity();
                return true;
            } catch (RestClientException e) {
                log.warn("[MQTT] Cannot create broker user {}: {}", user, e.getMessage());
                return false;
            }
        } catch (RestClientException e) {
            log.warn("[MQTT] Cannot update broker user {}: {}", user, e.getMessage());
            return false;
        }
    }

    /** True when the user was deleted; a missing user is not an error. */
    private boolean deleteUser(String user) {
        try {
            api.delete().uri(USERS + "/{id}", user).retrieve().toBodilessEntity();
            return true;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) log.warn("[MQTT] Cannot delete broker user {}: {}", user, e.getMessage());
            return false;
        } catch (RestClientException e) {
            log.warn("[MQTT] Cannot delete broker user {}: {}", user, e.getMessage());
            return false;
        }
    }

    private void kickClient(String clientId) {
        try {
            api.delete().uri("/clients/{id}", clientId).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound notConnected) {
            // not connected: nothing to do
        } catch (RestClientException e) {
            log.warn("[MQTT] Cannot disconnect {}: {}", clientId, e.getMessage());
        }
    }

    private static String stripSlash(String url) {
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static byte[] randomBytes(int n) {
        byte[] bytes = new byte[n];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
