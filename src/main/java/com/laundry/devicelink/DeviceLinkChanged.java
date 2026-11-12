package com.laundry.devicelink;

/**
 * Published when a device's approval or binding changed, so the link pushes the
 * new {@link LinkState} to the board once the transaction has committed.
 */
public record DeviceLinkChanged(String deviceId) {
}
