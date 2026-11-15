package com.laundry.iot.controller;

import com.laundry.iot.dto.ProvisioningDtos.RegisterChallenge;
import com.laundry.iot.dto.ProvisioningDtos.RegisterChallengeRequest;
import com.laundry.iot.dto.ProvisioningDtos.RegisterRefused;
import com.laundry.iot.dto.ProvisioningDtos.RegisterRequest;
import com.laundry.iot.service.DeviceRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by the boards themselves (no user login): a board proves its key and receives its own MQTT account.
 * Public on purpose, see {@link DeviceRegistrationService} for the limits.
 */
@RestController
@RequestMapping("/api/devices/register")
@RequiredArgsConstructor
@Tag(name = "Device registration (boards)", description = "ESP32 boards get their own MQTT account by signing a nonce")
public class DeviceRegistrationController {

    private final DeviceRegistrationService registration;

    @PostMapping("/challenge")
    @Operation(summary = "Single-use nonce (1 min) the board signs as LAUNDRYOS-REGISTER|<deviceId>|<nonce>")
    public RegisterChallenge challenge(@Valid @RequestBody RegisterChallengeRequest request, HttpServletRequest http) {
        return registration.challenge(request.deviceId(), http.getRemoteAddr());
    }

    @PostMapping
    @Operation(summary = "Signed registration: 200 with the board's MQTT account (password sealed to its key), "
            + "or 403 {state: AUTH_FAILED | REVOKED}")
    public ResponseEntity<Object> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        DeviceRegistrationService.Result result = registration.register(request, http.getRemoteAddr());
        return result.account()
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new RegisterRefused(result.refusedState().name(), result.refusalMessage())));
    }
}
