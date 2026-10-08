package com.laundry.mqtt.handler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * MQTT payload DTOs for the {@code /auth} channel introduced by the
 * QR check-in + physical START authorization feature.
 *
 * <p>These are sent by the ESP32 after the customer scans the QR and
 * presses the physical START button. The topic is
 * {@code laundry/washer/{branchId-UUID}/{machineId}/auth}.
 *
 * <p>A single DTO with a discriminator field would have been lighter on
 * the wire, but exposing a dedicated record per event keeps the firmware
 * contract obvious and lets the backend reject unknowns loudly.
 */
public class AuthChannelPayloads {

    /**
     * Sent by the device after receiving an {@code AUTHORIZE_START}
     * command. The {@code result} field is the device's verdict:
     * ACCEPTED, REJECTED, etc.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthAckPayload {
        private String type;             // always "AUTH_ACK"
        private UUID branchId;
        private String machineId;
        private UUID authorizationId;
        @JsonProperty("commandId")
        private String commandId;
        private Boolean success;
        private String result;           // ACCEPTED / REJECTED …
        private String reason;           // free text on rejection
        private String bootId;
        private Long timestamp;
    }

    /**
     * Sent by the device when the customer presses the physical START
     * button AND the device already has a valid authorization locally.
     *
     * <p>The backend uses this as a synchronous handoff: when it receives
     * {@code START_REQUEST} it transitions the authorization to
     * {@code START_PENDING}, then publishes {@code START_APPROVAL} back
     * so the firmware knows it may actually run the cycle.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StartRequestPayload {
        private String type;             // always "START_REQUEST"
        private String eventId;
        private UUID branchId;
        private String machineId;
        private UUID authorizationId;
        @JsonProperty("startRequestId")
        private String startRequestId;
        private String bootId;
        private Long timestamp;
    }

    /**
     * Sent by the device after it has actually started the washing
     * cycle (motors running, water valve opened, etc.). Drives the
     * {@code CONSUMED} transition.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StartedPayload {
        private String type;             // always "STARTED"
        private UUID branchId;
        private String machineId;
        private UUID authorizationId;
        @JsonProperty("startRequestId")
        private String startRequestId;
        private UUID runId;              // echoed back from START_APPROVAL
        private String bootId;
        private Long timestamp;
    }

    /**
     * Sent by the device when it REFUSES to start despite a valid
     * authorization (safety interlock, door open, STOP active, etc.).
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StartRejectedPayload {
        private String type;             // START_REJECTED
        private UUID branchId;
        private String machineId;
        private UUID authorizationId;
        @JsonProperty("startRequestId")
        private String startRequestId;
        private String reason;           // DOOR_OPEN, MACHINE_RUNNING, …
        private String bootId;
        private Long timestamp;
    }

    /**
     * Sent by the device when it accepted the START but failed
     * mid-launch (e.g. motor stall, leak detected during priming).
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StartFailedPayload {
        private String type;             // START_FAILED
        private UUID branchId;
        private String machineId;
        private UUID authorizationId;
        @JsonProperty("startRequestId")
        private String startRequestId;
        private String reason;
        private String bootId;
        private Long timestamp;
    }

    /**
     * Sent by the device when its local timer for the authorization
     * hit zero before the customer pressed START — the backend should
     * revoke the row so the next QR scan creates a fresh authorization.
     */
    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthExpiredPayload {
        private String type;             // AUTH_EXPIRED
        private UUID branchId;
        private String machineId;
        private UUID authorizationId;
        private String bootId;
        private Long timestamp;
    }
}
