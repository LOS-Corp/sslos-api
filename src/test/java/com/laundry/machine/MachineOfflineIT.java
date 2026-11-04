package com.laundry.machine;

import com.laundry.machine.runtime.MachineRuntimeState;
import com.laundry.machine.service.MachineService;
import com.laundry.support.Fixtures;
import com.laundry.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** BR-31, BR-24, SM-03: losing the heartbeat and coming back. */
@IntegrationTest
class MachineOfflineIT {

    @Autowired private MachineService machines;
    @Autowired private JdbcTemplate jdbc;

    private UUID branchId;
    private UUID machineId;
    private String code;

    @BeforeEach
    void setUp() {
        branchId = Fixtures.bookableBranch(jdbc, 50_000);
        machineId = Fixtures.washer(jdbc, branchId);
        code = jdbc.queryForObject("SELECT machine_code FROM machines WHERE id = ?", String.class, machineId);
    }

    @Test
    @DisplayName("BR-31: 30 s without a heartbeat → online=false and status OFFLINE; the next heartbeat brings it back")
    void heartbeatLostAndBack() {
        heartbeatLost();
        machines.detectOfflineMachines();
        assertThat(status()).isEqualTo("OFFLINE");
        assertThat(online()).isFalse();

        machines.processHeartbeat(branchId, code, MachineRuntimeState.builder().build());

        assertThat(status()).isEqualTo("AVAILABLE");
        assertThat(online()).isTrue();
    }

    @Test
    @DisplayName("A machine paused by an operator stays OUT_OF_SERVICE through an outage")
    void operatorPauseSurvivesAnOutage() {
        jdbc.update("UPDATE machines SET status = 'OUT_OF_SERVICE' WHERE id = ?", machineId);
        heartbeatLost();
        machines.detectOfflineMachines();
        assertThat(status()).isEqualTo("OUT_OF_SERVICE");
        assertThat(online()).isFalse();

        machines.processHeartbeat(branchId, code, MachineRuntimeState.builder().build());

        assertThat(status()).isEqualTo("OUT_OF_SERVICE");
        assertThat(online()).isTrue();
    }

    @Test
    @DisplayName("BR-24: a cycle that kept running through a network loss is seen again on reconnect")
    void runningThroughNetworkLoss() {
        jdbc.update("UPDATE machines SET status = 'RUNNING' WHERE id = ?", machineId);
        heartbeatLost();
        machines.detectOfflineMachines();
        assertThat(status()).isEqualTo("OFFLINE");

        machines.processHeartbeat(branchId, code, MachineRuntimeState.builder().status("RUNNING").build());
        assertThat(status()).isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("BR-24: a cycle that finished during the outage reports COMPLETED straight from OFFLINE")
    void completedAfterNetworkLoss() {
        jdbc.update("UPDATE machines SET status = 'OFFLINE', online = FALSE WHERE id = ?", machineId);

        machines.processStatus(branchId, code, MachineRuntimeState.builder().status("COMPLETED").build());

        assertThat(status()).isEqualTo("COMPLETED");
    }

    private void heartbeatLost() {
        jdbc.update("UPDATE machines SET last_heartbeat_at = NOW() - interval '1 minute' WHERE id = ?", machineId);
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM machines WHERE id = ?", String.class, machineId);
    }

    private boolean online() {
        return jdbc.queryForObject("SELECT online FROM machines WHERE id = ?", Boolean.class, machineId);
    }
}
