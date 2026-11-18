package com.laundry.machine.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Weighing before START on a washer (V61), shown on the machine page while the authorization is open.
 *
 * @param measuredKg  last scale reading; null until the customer pressed START with the laundry on the scale
 * @param paidKg      kilograms paid for (booked + extra already paid); null when the booking is not priced by weight
 * @param capacityKg  rated load of the machine; null when unknown
 * @param verdict     ACCEPTED, OVER_BOOKED or OVER_CAPACITY; null before weighing
 * @param extraKg     whole kilograms still to pay for the last reading
 * @param extraAmount what those kilograms cost
 * @param topUp       the unpaid top-up, once the customer chose to pay
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoadStatusResponse(
        BigDecimal measuredKg,
        BigDecimal paidKg,
        BigDecimal capacityKg,
        BigDecimal toleranceKg,
        String verdict,
        Integer extraKg,
        BigDecimal pricePerKg,
        BigDecimal extraAmount,
        OffsetDateTime checkedAt,
        TopUp topUp
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TopUp(UUID transactionId, int extraKg, BigDecimal amount, String status,
                        UUID paymentId, String paymentStatus, String paymentUrl) {
    }

    /** Body of {@code POST /api/machines/check-in/{id}/load/pay}; {@code useWallet} null = true. */
    public record PayRequest(Boolean useWallet) {
    }

    /** Body of {@code POST /api/machines/check-in/{id}/load/move}. */
    public record MoveRequest(@jakarta.validation.constraints.NotNull UUID machineId) {
    }

    /** The washer the slot moved to: the customer unlocks and weighs again there. */
    public record MoveResponse(String machineCode) {
    }
}
