package com.laundry.devicelink;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void parsesFramesAndKeepsLogLinesOut() {
        DeviceFrame ack = DeviceFrame.parse("@command/ack {\"commandId\":\"C1\"}\r").orElseThrow();
        assertEquals("command/ack", ack.channel());
        assertEquals("{\"commandId\":\"C1\"}", ack.payload());
        assertEquals(DeviceChannel.COMMAND_ACK, DeviceChannel.fromPath(ack.channel()).orElseThrow());
        assertEquals(DeviceChannel.AUTH, DeviceChannel.fromPath("auth").orElseThrow());
        assertEquals("{}", DeviceFrame.parse("@hello").orElseThrow().payload());

        assertTrue(DeviceFrame.parse("[WIFI] booting...").isEmpty());   // debug log
        assertTrue(DeviceFrame.parse("@status not-json").isEmpty());
        assertTrue(DeviceFrame.parse("@").isEmpty());
        assertTrue(DeviceFrame.parse("").isEmpty());
        assertTrue(DeviceChannel.fromPath("unknown").isEmpty());
    }

    @Test
    void formatsBackendFrames() {
        assertEquals("@cmd {\"type\":\"STOP\"}", new DeviceFrame(DeviceFrame.COMMAND, "{\"type\":\"STOP\"}").format());
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
