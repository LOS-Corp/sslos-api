package com.laundry.mqtt.service;

/**
 * Centralized MQTT topic constants for the branch-scoped architecture.
 */
public final class MqttTopics {

    public static final String ROOT = "laundry/washer";

    /**
     * Wildcard subscription covering every branch, every machine, and every channel:
     * {@code laundry/washer/+/+/#}
     */
    public static final String BACKEND_SUBSCRIPTION = ROOT + "/+/+/#";

    private MqttTopics() {}
}