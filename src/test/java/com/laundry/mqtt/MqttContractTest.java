package com.laundry.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.laundry.machine.runtime.MachineRuntimeState;
import com.laundry.mqtt.handler.WasherPayloads.CommandAckPayload;
import com.laundry.mqtt.handler.WasherPayloads.DeviceSnapshot;
import com.laundry.mqtt.service.MqttChannel;
import com.laundry.mqtt.service.MqttTopicParser;
import com.laundry.mqtt.service.ParsedMqttTopic;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqttContractTest {

    private static final String BRANCH = "00000000-0000-0000-0000-000000000001";

    private final MqttTopicParser parser = new MqttTopicParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesDeviceChannels() {
        ParsedMqttTopic ack = parser.parse("laundry/washer/" + BRANCH + "/WM001/command/ack").orElseThrow();
        assertEquals(UUID.fromString(BRANCH), ack.branchId());
        assertEquals("WM001", ack.machineId());
        assertEquals(MqttChannel.COMMAND_ACK, ack.channel());

        assertEquals(MqttChannel.AUTH, parser.parse("laundry/washer/" + BRANCH + "/WM001/auth").orElseThrow().channel());
    }

    @Test
    void ignoresOwnCommandsAndInvalidTopics() {
        assertTrue(parser.parse("laundry/washer/" + BRANCH + "/WM001/command").isEmpty());
        assertTrue(parser.parse("laundry/washer/BR001/WM001/status").isEmpty());
        assertTrue(parser.parse("laundry/washer/" + BRANCH + "//status").isEmpty());
        assertTrue(parser.parse("laundry/washer/" + BRANCH + "/WM001/unknown").isEmpty());
        assertTrue(parser.parse("laundry/washer/" + BRANCH + "/WM001/log").isEmpty());  // debug log, not processed
        assertTrue(parser.parse("laundry/dryer/" + BRANCH + "/WM001/status").isEmpty());
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
