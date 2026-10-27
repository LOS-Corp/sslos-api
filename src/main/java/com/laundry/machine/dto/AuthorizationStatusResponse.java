package com.laundry.machine.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.laundry.machine.entity.MachineStartAuthorizationStatus;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Public view of an authorization — used by the polling endpoint
 * {@code GET /api/machines/check-in/{authorizationId}}.
 */
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthorizationStatusResponse(
        UUID authorizationId,
        UUID bookingId,
        UUID machineId,
        String machineCode,
        MachineStartAuthorizationStatus authorizationStatus,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        OffsetDateTime grantedAt,
        OffsetDateTime consumedAt,
        OffsetDateTime revokedAt,
        String revokeReason
) {}
