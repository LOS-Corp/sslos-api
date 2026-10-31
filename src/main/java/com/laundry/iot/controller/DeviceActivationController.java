package com.laundry.iot.controller;

import com.laundry.iot.dto.ProvisioningDtos.*;
import com.laundry.iot.service.DeviceProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Stages 5–6, called by the ESP32 itself over HTTPS (no JWT). The device
 * authenticates with an HMAC over a single-use nonce, and only while an admin
 * pairing session for it is open.
 */
@RestController
@RequestMapping("/api/devices/activate")
@RequiredArgsConstructor
@Tag(name = "Device Activation", description = "ESP32 challenge-response activation")
public class DeviceActivationController {

    private final DeviceProvisioningService provisioningService;

    @PostMapping("/challenge")
    @Operation(summary = "Get a single-use nonce (only while a pairing session is open)")
    public ChallengeResponse challenge(@Valid @RequestBody ChallengeRequest request) {
        return provisioningService.challenge(request.deviceId());
    }

    @PostMapping
    @Operation(summary = "Prove possession of the claim secret and receive per-device MQTT credentials")
    public ActivateResponse activate(@Valid @RequestBody ActivateRequest request) {
        return provisioningService.activate(request);
    }
}
