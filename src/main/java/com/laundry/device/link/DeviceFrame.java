package com.laundry.device.link;

import java.util.Set;

/** Tên kênh của giao thức bo mạch; mỗi kênh là một topic MQTT, mỗi tin là một JSON object. */
public final class DeviceFrame {

    /** Bo -> backend: {deviceId, firmwareVersion, deviceType, publicKey, ip, rssi}. */
    public static final String HELLO = "hello";
    /** Bo -> backend: {nonce, signature} trả lời CHALLENGE. */
    public static final String PROOF = "proof";
    /** Backend -> bo. */
    public static final String LINK = "link";
    public static final String CHALLENGE = "challenge";
    public static final String COMMAND = "command";
    public static final String NETWORK = "netcfg";

    /** Kênh bo gửi lên sau khi đã xác thực và được gắn máy/tủ. */
    public static final Set<String> DEVICE_CHANNELS = Set.of("status", "telemetry", "heartbeat", "event", "auth",
            "command/ack");

    public static final int MAX_PAYLOAD = 4096;

    private DeviceFrame() {
    }

    public static boolean isJsonObject(String payload) {
        return payload != null && payload.length() <= MAX_PAYLOAD && payload.startsWith("{") && payload.endsWith("}");
    }
}
