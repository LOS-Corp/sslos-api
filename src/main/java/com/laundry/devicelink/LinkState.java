package com.laundry.devicelink;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * What the backend tells a board about itself in a {@code @link} frame, on
 * HELLO, after every change and as a keepalive. The board shows the state on
 * its screen and only accepts START while it is {@link State#BOUND} and the
 * link is fresh.
 *
 * <p>{@code qrUrl} is set only while the bound machine is commissioned (ACTIVE):
 * the washer and dryer draw it as a QR code on their idle screen, so customers
 * scan the machine itself instead of a printed label.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LinkState(State state, String deviceId, UUID branchId, String machineCode, String machineType,
                        String qrUrl) {

    public enum State {
        /** The backend does not know this connection yet: send HELLO. */
        HELLO_REQUIRED,
        /** Seen for the first time: waiting for an admin to approve the board. */
        PENDING,
        /** Approved, not attached to a machine yet. */
        VERIFIED,
        /** Attached to {@code machineCode}: normal operation. */
        BOUND,
        /** Withdrawn by an admin: the board must stay idle until approved again. */
        REVOKED,
        /** The same deviceId is already connected on another port. */
        DUPLICATE
    }

    public static LinkState of(State state, String deviceId) {
        return new LinkState(state, deviceId, null, null, null, null);
    }

    public static LinkState bound(String deviceId, UUID branchId, String machineCode, String machineType) {
        return bound(deviceId, branchId, machineCode, machineType, null);
    }

    public static LinkState bound(String deviceId, UUID branchId, String machineCode, String machineType,
                                  String qrUrl) {
        return new LinkState(State.BOUND, deviceId, branchId, machineCode, machineType, qrUrl);
    }

    public boolean isBound() {
        return state == State.BOUND && branchId != null && machineCode != null;
    }
}
