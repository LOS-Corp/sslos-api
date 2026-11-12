package com.laundry.iot.firmware;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Progress of a firmware update started from the web. The page polls it.
 *
 * @param state    FLASHING (esptool running) → REBOOTING (waiting for HELLO with the new version) → DONE,
 *                 or FAILED at any step
 * @param progress 0–100 while writing, from esptool's "Writing at … (n %)" lines
 * @param log      the last lines esptool printed
 */
public record FirmwareUpdateStatus(String deviceId, String port, State state, int progress,
                                   String fromVersion, String toVersion, String message, List<String> log,
                                   OffsetDateTime startedAt, OffsetDateTime finishedAt) {

    public enum State { FLASHING, REBOOTING, DONE, FAILED }

    public boolean finished() {
        return state == State.DONE || state == State.FAILED;
    }
}
