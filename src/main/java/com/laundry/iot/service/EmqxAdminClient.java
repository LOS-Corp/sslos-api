package com.laundry.iot.service;

import com.laundry.iot.config.EmqxAdminProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin client for the EMQX v5 management API: per-device users in the
 * built-in password database, per-username ACL rules, and client kick-out.
 *
 * <p>In {@link EmqxAdminProperties.Mode#MANUAL} every call is a logged no-op;
 * the admin registers the credential in the EMQX console instead.
 */
@Component
@Slf4j
public class EmqxAdminClient {

    /** Device-side channels; must match MqttTopicBuilder / MqttTopicParser. */
    private static final List<String> DEVICE_PUBLISH_CHANNELS =
            List.of("status", "heartbeat", "telemetry", "event", "auth", "command/ack");

    private final EmqxAdminProperties properties;
    private final RestClient restClient;

    public EmqxAdminClient(EmqxAdminProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .defaultHeaders(h -> h.setBasicAuth(properties.getApiKey(), properties.getApiSecret()))
                .build();
    }

    public boolean isManual() {
        return properties.getProvisioningMode() == EmqxAdminProperties.Mode.MANUAL;
    }

    /** Create the device user, or reset its password when it already exists. */
    public void upsertUser(String username, String password) {
        if (skip("upsert user " + username)) return;
        try {
            restClient.post().uri(url(authUsersPath()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("user_id", username, "password", password, "is_superuser", false))
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.CONFLICT) throw failure("create user", e);
            call("update user", () -> restClient.put().uri(url(authUsersPath() + "/" + enc(username)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("password", password, "is_superuser", false))
                    .retrieve().toBodilessEntity());
        } catch (RestClientException e) {
            throw failure("create user", e);
        }
    }

    /**
     * Replace the device's ACL with least privilege: publish only on its own
     * device channels, subscribe only to its own command topic, deny the rest.
     */
    public void replaceDeviceAcl(String username, String topicBase) {
        if (skip("replace ACL for " + username)) return;
        List<Map<String, Object>> rules = new ArrayList<>();
        for (String channel : DEVICE_PUBLISH_CHANNELS) {
            rules.add(rule(topicBase + "/" + channel, "allow", "publish"));
        }
        rules.add(rule(topicBase + "/command", "allow", "subscribe"));
        rules.add(rule("#", "deny", "all"));
        Map<String, Object> entry = Map.of("username", username, "rules", rules);

        String path = "/authorization/sources/built_in_database/rules/users";
        try {
            restClient.put().uri(url(path + "/" + enc(username)))
                    .contentType(MediaType.APPLICATION_JSON).body(entry)
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            call("create ACL", () -> restClient.post().uri(url(path))
                    .contentType(MediaType.APPLICATION_JSON).body(List.of(entry))
                    .retrieve().toBodilessEntity());
        } catch (RestClientException e) {
            throw failure("replace ACL", e);
        }
    }

    /** Delete user + ACL and disconnect any live session. Missing entries are ignored. */
    public void revoke(String username, String clientId) {
        if (skip("revoke " + username)) return;
        deleteIgnoringMissing("delete user", authUsersPath() + "/" + enc(username));
        deleteIgnoringMissing("delete ACL",
                "/authorization/sources/built_in_database/rules/users/" + enc(username));
        deleteIgnoringMissing("kick client", "/clients/" + enc(clientId));
    }

    private void deleteIgnoringMissing(String action, String path) {
        try {
            restClient.delete().uri(url(path)).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            log.debug("EMQX {}: already absent ({})", action, path);
        } catch (RestClientException e) {
            throw failure(action, e);
        }
    }

    private void call(String action, Runnable request) {
        try {
            request.run();
        } catch (RestClientException e) {
            throw failure(action, e);
        }
    }

    private boolean skip(String action) {
        if (!isManual()) return false;
        log.info("EMQX provisioning mode MANUAL — skipping '{}'; apply it in the EMQX console", action);
        return true;
    }

    private String authUsersPath() {
        return "/authentication/" + enc(properties.getAuthenticatorId()) + "/users";
    }

    /** Pre-encoded URI so RestClient does not re-encode the '%3A' in the authenticator id. */
    private URI url(String path) {
        String base = properties.getApiBaseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("EMQX_API_BASE_URL is not configured (app.emqx.api-base-url)");
        }
        return URI.create(base.replaceAll("/+$", "") + path);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Map<String, Object> rule(String topic, String permission, String action) {
        return Map.of("topic", topic, "permission", permission, "action", action);
    }

    private static IllegalStateException failure(String action, RestClientException e) {
        log.error("EMQX management API failed to {}: {}", action, e.getMessage());
        return new IllegalStateException("EMQX management API failed to " + action + ": " + e.getMessage(), e);
    }
}
