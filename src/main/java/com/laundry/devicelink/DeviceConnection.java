package com.laundry.devicelink;

/**
 * One board's MQTT session on the store's local broker, as seen by the {@link DeviceLinkHub}.
 * Implementations must make {@link #send} safe to call from several threads.
 */
public interface DeviceConnection {

    /**
     * The only deviceId this session may speak for: its MQTT client id, which the broker's ACL bound
     * to the topics it publishes on. A broker password alone does not prove the board, so its HELLO is only
     * accepted after it signed a nonce with its pinned key.
     */
    String deviceId();

    /** Publishes {@code json} on the board's {@code down/<channel>} topic. */
    void send(String channel, String json);
}
