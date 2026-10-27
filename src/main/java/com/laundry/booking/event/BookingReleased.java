package com.laundry.booking.event;

import java.util.UUID;

/**
 * A paid booking gave its machines back before using them (customer cancellation, UC-08). Published inside
 * the cancelling transaction; listeners act after commit (e.g. the machine module revokes START).
 */
public record BookingReleased(UUID bookingId, String reason) {
}
