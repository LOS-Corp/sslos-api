package com.laundry.devicelink.mqtt;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Link to the boards through the store's local EMQX broker. Prefix: {@code app.mqtt}.
 * The topic tree is fixed, see {@link MqttTopics}.
 */
@Component
@ConfigurationProperties(prefix = "app.mqtt")
@Getter
@Setter
public class MqttLinkProperties {

    /** Connect to the broker at all; false only in tests. */
    private boolean enabled = true;

    /** The backend's broker URL, e.g. {@code tcp://emqx:1883} inside Docker. */
    private String brokerUrl = "tcp://localhost:1883";

    /** EMQX REST API (user management), e.g. {@code http://emqx:18083} inside Docker. */
    private String apiUrl = "http://127.0.0.1:18083";
    /** EMQX API key and secret (role administrator), bootstrapped by {@code emqx/start.sh} from the same values. */
    private String apiKey = "";
    private String apiSecret = "";

    /** The backend's own broker user (superuser), created by the backend through the API before it connects. */
    private String username = "laundryos-server";
    private String password = "";
    /**
     * The backend's MQTT client id. Empty = {@code laundryos-server-<random>} on every start, so no other client
     * can take the backend's session over by reusing its id (MQTT drops the older of two sessions with one id).
     */
    private String clientId = "";

    /** QoS of backend → board messages. */
    private int qos = 1;

    /** The link state is re-sent this often; a board treats 30 s without any message as link lost. */
    private long keepaliveMs = 10_000;

    // What the technician types into a board's setup page: the store server as the boards reach it on the LAN.

    /** LAN address of the store server, e.g. {@code 192.168.1.10}. */
    private String deviceHost = "";
    /** The broker's TLS port for the boards; the backend itself uses the plain port of {@link #brokerUrl}. */
    private int devicePort = 8883;
    /** This API's port as the boards reach it on the store LAN ({@code POST /api/devices/register}). */
    private int deviceApiPort = 8080;
}
