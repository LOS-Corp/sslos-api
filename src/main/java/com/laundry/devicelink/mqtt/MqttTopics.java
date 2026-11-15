package com.laundry.devicelink.mqtt;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The MQTT topic tree of the device link. The same names are hard-coded in the firmware
 * ({@code sslos-iot/common/NetLink.h}) and in the broker ACL ({@code emqx/acl.conf}).
 *
 * <pre>
 * laundryos/v1/devices/{deviceId}/presence          board, retained: "online" / last will "offline"   QoS 1
 * laundryos/v1/devices/{deviceId}/up/{channel}      board → backend, one JSON object                    QoS 0
 *     hello, proof, status, telemetry, heartbeat, event, auth, command/ack
 * laundryos/v1/devices/{deviceId}/down/{channel}    backend → board, one JSON object                    QoS 1
 *     link, challenge, command, netcfg
 * </pre>
 *
 * <p>{@code {deviceId}} ({@code ESP-<12 hex>}) is also the board's MQTT client id and its broker username
 * (see {@link BrokerAccounts}); the broker's ACL only lets a board publish on its own {@code up/#} and
 * {@code presence} and read its own {@code down/#}.
 * {@code v1} is the protocol version: an incompatible change gets a new tree instead of breaking old boards.
 */
public final class MqttTopics {

    public static final String ROOT = "laundryos/v1/devices";
    public static final String PRESENCE = "presence";
    public static final String UP = "up";
    public static final String DOWN = "down";

    /** What the backend subscribes to. */
    public static final String SUBSCRIBE_UP = ROOT + "/+/" + UP + "/#";
    public static final String SUBSCRIBE_PRESENCE = ROOT + "/+/" + PRESENCE;

    public static final String ONLINE = "online";
    public static final String OFFLINE = "offline";

    private static final Pattern INBOUND = Pattern.compile(
            "^" + Pattern.quote(ROOT) + "/([^/]+)/(?:(" + PRESENCE + ")|" + UP + "/([a-z]+(?:/[a-z]+)?))$");

    private MqttTopics() {
    }

    public static String presence(String deviceId) {
        return ROOT + "/" + deviceId + "/" + PRESENCE;
    }

    public static String up(String deviceId, String channel) {
        return ROOT + "/" + deviceId + "/" + UP + "/" + channel;
    }

    public static String down(String deviceId, String channel) {
        return ROOT + "/" + deviceId + "/" + DOWN + "/" + channel;
    }

    /** A topic the backend receives: {@code channel} is {@code null} for presence. */
    public record Inbound(String deviceId, String channel) {
        public boolean isPresence() {
            return channel == null;
        }
    }

    /** Empty for any topic outside the inbound tree (or a malformed channel). */
    public static Optional<Inbound> parse(String topic) {
        if (topic == null) return Optional.empty();
        Matcher m = INBOUND.matcher(topic);
        if (!m.matches()) return Optional.empty();
        return Optional.of(new Inbound(m.group(1), m.group(2) != null ? null : m.group(3)));
    }
}
