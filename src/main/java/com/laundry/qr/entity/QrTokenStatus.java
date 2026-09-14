package com.laundry.qr.entity;

/**
 * QR Token status
 */
public enum QrTokenStatus {
    ACTIVE,   // Token is valid and can be used
    USED,     // Token has been used
    EXPIRED,  // Token has expired
    REVOKED   // Token was manually revoked
}
