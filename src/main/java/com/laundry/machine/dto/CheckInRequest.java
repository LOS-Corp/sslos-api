package com.laundry.machine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for {@code POST /api/machines/check-in}.
 *
 * <p>{@code machineCode} identifies which machine the QR belongs to.
 * The customer identity is taken from Spring Security on the server
 * side — the frontend MUST NOT be allowed to submit {@code userId},
 * {@code reservationId}, or any ownership claim.
 */
public record CheckInRequest(
        @NotBlank @Size(max = 50) String machineCode
) {}
