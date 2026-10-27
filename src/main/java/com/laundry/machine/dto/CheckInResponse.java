package com.laundry.machine.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laundry.machine.entity.MachineStartAuthorizationStatus;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Result of a successful QR check-in.
 * Returned by {@code POST /api/machines/check-in}.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckInResponse(
        UUID authorizationId,
        UUID bookingId,
        UUID machineId,
        String machineCode,
        MachineStartAuthorizationStatus authorizationStatus,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        /** Seconds remaining until expiration at the moment the response was produced. */
        long remainingSeconds
) {}
