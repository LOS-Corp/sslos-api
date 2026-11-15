package com.laundry.devicelink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.devicelink.mqtt.MqttTopics;
import com.laundry.machine.runtime.MachineRuntimeState;
import com.laundry.devicelink.handler.WasherPayloads.CommandAckPayload;
import com.laundry.devicelink.handler.WasherPayloads.DeviceSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void topicTreeIsVersionedAndSplitByDirectionAndChannel() {
        String id = "ESP-24A1B2C3D4E5";
        assertEquals("laundryos/v1/devices/ESP-24A1B2C3D4E5/presence", MqttTopics.presence(id));
        assertEquals("laundryos/v1/devices/ESP-24A1B2C3D4E5/up/command/ack", MqttTopics.up(id, "command/ack"));
        assertEquals("laundryos/v1/devices/ESP-24A1B2C3D4E5/down/command", MqttTopics.down(id, DeviceFrame.COMMAND));
        assertEquals("laundryos/v1/devices/+/up/#", MqttTopics.SUBSCRIBE_UP);
    }

    @Test
    void parsesInboundTopicsOnly() {
        MqttTopics.Inbound ack = MqttTopics.parse("laundryos/v1/devices/ESP-24A1B2C3D4E5/up/command/ack").orElseThrow();
        assertEquals("ESP-24A1B2C3D4E5", ack.deviceId());
        assertEquals(DeviceChannel.COMMAND_ACK, DeviceChannel.fromPath(ack.channel()).orElseThrow());
        assertEquals(DeviceChannel.AUTH, DeviceChannel.fromPath("auth").orElseThrow());
        assertTrue(MqttTopics.parse("laundryos/v1/devices/ESP-24A1B2C3D4E5/presence").orElseThrow().isPresence());

        assertTrue(MqttTopics.parse("laundryos/v1/devices/ESP-24A1B2C3D4E5/down/command").isEmpty());  // our own output
        assertTrue(MqttTopics.parse("laundryos/v1/devices/ESP-24A1B2C3D4E5/up").isEmpty());
        assertTrue(MqttTopics.parse("laundryos/v1/devices/ESP-24A1B2C3D4E5/up/a/b/c").isEmpty());
        assertTrue(MqttTopics.parse("laundryos/dev/ESP-24A1B2C3D4E5/up").isEmpty());                // old tree
        assertTrue(DeviceChannel.fromPath("unknown").isEmpty());
    }

    @Test
    void acceptsOnlyJsonObjectPayloads() {
        assertTrue(DeviceFrame.isJsonObject("{\"status\":\"AVAILABLE\"}"));
        assertFalse(DeviceFrame.isJsonObject("not-json"));
        assertFalse(DeviceFrame.isJsonObject(""));
        assertFalse(DeviceFrame.isJsonObject("{" + "x".repeat(DeviceFrame.MAX_PAYLOAD) + "}"));
    }

    @Test
    void snapshotAcceptsFirmwareErrorText() throws Exception {
        MachineRuntimeState state = objectMapper.readValue(
                "{\"status\":\"ERROR\",\"error\":\"DOOR_OPEN\",\"weight\":3.5,\"waterLevelLiters\":null}",
                DeviceSnapshot.class).toRuntimeState();

        assertTrue(state.getError());
        assertEquals("DOOR_OPEN", state.getErrorMessage());
        assertEquals(BigDecimal.valueOf(3.5), state.getWeightKg());
        assertNull(state.getWaterLevelLiters());

        MachineRuntimeState healthy = objectMapper.readValue("{\"error\":\"\"}", DeviceSnapshot.class).toRuntimeState();
        assertFalse(healthy.getError());
        // Empty text clears a stale error_code after RESET; a missing field leaves it untouched.
        assertEquals("", healthy.getErrorMessage());
        assertNull(objectMapper.readValue("{\"status\":\"AVAILABLE\"}", DeviceSnapshot.class)
                .toRuntimeState().getErrorMessage());
    }

    @Test
    void commandAckPrefersSuccessFlag() throws Exception {
        CommandAckPayload ack = objectMapper.readValue(
                "{\"commandId\":\"CMD-1\",\"success\":true,\"reason\":\"\"}", CommandAckPayload.class);
        assertEquals("CMD-1", ack.correlationId());
        assertTrue(ack.accepted());

        CommandAckPayload legacy = objectMapper.readValue("{\"requestId\":\"R-1\",\"result\":\"ok\"}", CommandAckPayload.class);
        assertEquals("R-1", legacy.correlationId());
        assertTrue(legacy.accepted());
    }
}
