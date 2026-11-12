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
     * @throws DeviceNotConnectedException if no board for this machine is plugged in
     */
    void send(Machine machine, String json);

    /** True while the board with this id is plugged in and has said HELLO. */
    boolean isConnected(String deviceId);

    /** Serial port the board is plugged into, or {@code null} when it is not connected. */
    String portOf(String deviceId);
}
