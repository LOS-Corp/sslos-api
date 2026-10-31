package com.laundry.machine.entity;

/**
 * State of a {@link MachineStartAuthorization}.
 *
 * <pre>
 *   PENDING        ─ created on QR check-in; awaiting device AUTH_ACK
 *   GRANTED        ─ device confirmed AUTH_ACK; physical START button unlocked
 *   START_PENDING  ─ customer pressed START; awaiting device STARTED ack
 *   CONSUMED       ─ device confirmed cycle started; terminal (success)
 *   EXPIRED        ─ expiresAt passed with no successful start; terminal
 *   REVOKED        ─ backend or customer cancelled; terminal
 * </pre>
 */
public enum MachineStartAuthorizationStatus {
    PENDING,
    GRANTED,
    START_PENDING,
    CONSUMED,
    EXPIRED,
    REVOKED
}
