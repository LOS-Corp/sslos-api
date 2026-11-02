package com.laundry.locker.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class LockerDtos {
    private LockerDtos() {
    }

    /**
     * What the customer needs at the locker for one assisted-laundry booking.
     * {@code stage}: NOT_LINKED, PAYMENT, DROP_OFF, WAITING_STAFF, PICKUP, DONE, CLOSED.
     * {@code pin} is only set in DROP_OFF and PICKUP.
     */
    public record CustomerLockerAccess(
            UUID bookingId,
            String stage,
            String lockerCode,
            String lockerName,
            Integer compartmentNo,
            String compartmentName,
            String pin,
            Instant validFrom,
            Instant validUntil,
            boolean lockerOnline
    ) {
    }

    /**
     * Live door state the cabinet reports for one compartment; {@code key} (A, B, ...) and
     * the GPIOs say which physical door it is.
     */
    public record CompartmentState(int no, boolean doorClosed, boolean unlocked,
                                   String key, Integer lockGpio, Integer doorGpio) {
    }

    public record CompartmentBooking(
            UUID bookingId,
            String shortCode,
            String customerName,
            String customerPhone,
            String status,
            Instant startTime
    ) {
    }

    public record CompartmentDto(
            int no,
            UUID resourceId,
            String resourceCode,
            String displayName,
            Boolean doorClosed,
            Boolean unlocked,
            /** Keypad key that picks this compartment; null until the cabinet reported. */
            String key,
            Integer lockGpio,
            Integer doorGpio,
            CompartmentBooking booking
    ) {
    }

    public record LockerCabinetDto(
            UUID machineId,
            String machineCode,
            String displayName,
            UUID branchId,
            boolean online,
            boolean commissioned,
            String status,
            List<CompartmentDto> compartments
    ) {
    }

    /** COLLECT: take the customer's laundry out; RETURN: put the clean laundry back; INSPECT: just open. */
    public record StaffOpenRequest(@NotNull String action) {
    }

    public record SyncCompartmentsRequest(@Min(1) @Max(8) int count) {
    }
}
