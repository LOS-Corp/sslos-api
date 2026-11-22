package com.laundry.machine.service;

/**
 * A device command reached its final state: the device accepted or rejected it, it could not be sent, or its ACK
 * never came. Published synchronously inside the transaction that recorded the state, so a listener (V66: a paid
 * extra drying time) can settle what depended on it.
 *
 * @param outcome ACKNOWLEDGED, REJECTED, FAILED or TIMEOUT
 */
public record DeviceCommandFinished(String commandId, String command, String outcome, String reason) {

    public boolean accepted() {
        return "ACKNOWLEDGED".equals(outcome);
    }

    /** The device certainly did not apply the command (it refused it, or never received it). */
    public boolean certainlyNotApplied() {
        return "REJECTED".equals(outcome) || "FAILED".equals(outcome);
    }
}
