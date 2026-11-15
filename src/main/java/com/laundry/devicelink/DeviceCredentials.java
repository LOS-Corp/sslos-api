package com.laundry.devicelink;

import java.util.Optional;

/**
 * Per-board broker accounts (username = client id = deviceId), handed out by {@code POST /api/devices/register};
 * revoking the board removes its account on the broker, which disconnects it at once.
 */
public interface DeviceCredentials {

    /** Creates or re-keys the board's own account; the new password, or empty when the broker could not be updated. */
    Optional<String> issue(String deviceId);

    /** Disables the board's own account (it is disconnected now and cannot log in again). */
    void revoke(String deviceId);
}
