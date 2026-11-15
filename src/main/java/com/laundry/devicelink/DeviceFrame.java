package com.laundry.devicelink;

/**
 * Channel names of the device protocol. Each channel is its own MQTT topic and carries one JSON object
 * (see {@link com.laundry.devicelink.mqtt.MqttTopics}).
 *
 * <p>Board → backend: {@link #HELLO}, {@link #PROOF} and the {@link DeviceChannel} paths.
 * Backend → board: {@link #LINK}, {@link #CHALLENGE}, {@link #COMMAND} and {@link #NETWORK}.
 */
public final class DeviceFrame {

    /** Board → backend: {@code {deviceId, firmwareVersion, deviceType, publicKey, ip, rssi}}. */
    public static final String HELLO = "hello";
    /** Board → backend: {@code {nonce, signature}} answering {@link #CHALLENGE}. */
    public static final String PROOF = "proof";
    /** Backend → board: binding state {@code {state, machineCode, machineType, qrUrl}}; also the keepalive. */
    public static final String LINK = "link";
    /** Backend → board: {@code {nonce}} to sign before the HELLO is accepted. */
    public static final String CHALLENGE = "challenge";
    /** Backend → board: a machine command (AUTHORIZE_START, STOP, SELF_TEST, ...). */
    public static final String COMMAND = "command";
    /** Backend → board: network settings, e.g. {@code {"action":"RESET"}} back to setup mode. */
    public static final String NETWORK = "netcfg";

    /** Largest payload accepted from a board; larger messages are dropped. */
    public static final int MAX_PAYLOAD = 4096;

    private DeviceFrame() {
    }

    /** A payload the hub accepts: a JSON object no larger than {@link #MAX_PAYLOAD}. */
    public static boolean isJsonObject(String payload) {
        return payload != null && payload.length() <= MAX_PAYLOAD && payload.startsWith("{") && payload.endsWith("}");
    }
}
