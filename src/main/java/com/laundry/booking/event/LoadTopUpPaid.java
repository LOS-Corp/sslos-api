package com.laundry.booking.event;

import java.util.UUID;

/**
 * The customer paid for extra kilograms at the washer (V61). Published inside the confirming transaction;
 * the machine module re-weighs the last scale reading after commit and unlocks START when it now fits.
 */
public record LoadTopUpPaid(UUID bookingId, UUID authorizationId) {
}
