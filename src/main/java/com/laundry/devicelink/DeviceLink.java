package com.laundry.devicelink;

import com.laundry.machine.entity.Machine;

/**
 * The only way the system sends commands to devices. Clients never talk to a
 * board directly, so every command passes the backend's authorization and audit first.
 */
public interface DeviceLink {

    /**
     * Sends a JSON command to the board bound to {@code machine}.
     *
     * @throws DeviceNotConnectedException if the machine's board is not on the broker
     */
    void send(Machine machine, String json);

    /** True while the board with this id is on the broker and its HELLO was accepted. */
    boolean isConnected(String deviceId);

    /**
     * Sends a link-level frame (not a machine command) to a connected board, e.g.
     * {@code netcfg {"action":"RESET"}}. Works whether or not the board is attached to a machine.
     *
     * @throws DeviceNotConnectedException if the board is not connected
     */
    void sendFrame(String deviceId, String channel, String json);
}
