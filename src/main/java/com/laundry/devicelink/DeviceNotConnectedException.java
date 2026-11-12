package com.laundry.devicelink;

/** The machine has no bound board, or its board is not plugged in right now. */
public class DeviceNotConnectedException extends IllegalStateException {

    public DeviceNotConnectedException(String message) {
        super(message);
    }
}
