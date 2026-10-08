package com.laundry.mqtt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Valid channel types for laundry machine MQTT topics.
 *
 * <p>Contract: {@code laundry/washer/{branchId}/{machineId}/{channel}}
 */
public enum MqttChannel {
    STATUS,
    TELEMETRY,
    EVENT,
    COMMAND,
    HEARTBEAT,
    /**
     * QR check-in + physical START authorization channel.
     * Device-publishes:
     *   AUTH_ACK         — boot-time acknowledgement of the previous AUTHORIZE_START.
     *   START_REQUEST    — customer pressed the physical START button.
     *   STARTED          — cycle actually started on the device.
     *   START_REJECTED   — device refused to start (safety / mismatch).
     *   START_FAILED     — device accepted then failed mid-launch.
     *   AUTH_EXPIRED     — boot-id changed or local timer expired before START_REQUEST.
     */
    AUTH
}