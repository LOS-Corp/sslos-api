package com.laundry.device.link;

/** Một phiên MQTT của một bo, nhìn từ hub. */
public interface DeviceConnection {

    String deviceId();

    /** Gửi một JSON lên down/{channel}; ném IllegalStateException khi broker mất kết nối. */
    void send(String channel, String json);
}
