package com.laundry.machine.controller;

import com.laundry.booking.service.LoadMoveService.LargerWasher;
import com.laundry.machine.dto.LoadStatusResponse.MoveRequest;
import com.laundry.machine.dto.LoadStatusResponse.MoveResponse;
import com.laundry.machine.dto.LoadStatusResponse.PayRequest;
import com.laundry.machine.service.MachineLoadService;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The customer's choices after the washer weighed the laundry (V61). The weighing result itself is part of
 * {@code GET /api/machines/check-in/{authorizationId}} ({@code load}).
 */
@RestController
@RequestMapping("/api/machines/check-in/{authorizationId}/load")
@RequiredArgsConstructor
@Tag(name = "Machine load", description = "Wash by weight: pay for extra kilograms or move to a larger washer")
public class MachineLoadController {

    private final MachineLoadService loadService;

    @PostMapping("/pay")
    @Operation(summary = "Pay for the extra kilograms of the last weighing (wallet first, then payOS)")
    public PaymentResponse pay(@PathVariable UUID authorizationId, @RequestBody(required = false) PayRequest request,
                               CurrentUser customer) {
        boolean useWallet = request == null || !Boolean.FALSE.equals(request.useWallet());
        return loadService.payExtra(authorizationId, customer.id(), useWallet);
    }

    @GetMapping("/larger-machines")
    @Operation(summary = "Free washers large enough for the weighed laundry")
    public List<LargerWasher> largerMachines(@PathVariable UUID authorizationId, CurrentUser customer) {
        return loadService.largerMachines(authorizationId, customer.id());
    }

    @PostMapping("/move")
    @Operation(summary = "Move the washer slot to a larger free washer; unlock and weigh again there")
    public MoveResponse move(@PathVariable UUID authorizationId, @Valid @RequestBody MoveRequest request,
                             CurrentUser customer) {
        return loadService.move(authorizationId, customer.id(), request.machineId());
    }
}
