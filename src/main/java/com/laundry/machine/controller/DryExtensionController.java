package com.laundry.machine.controller;

import com.laundry.booking.service.DryExtensionService;
import com.laundry.booking.service.DryExtensionService.Extension;
import com.laundry.booking.service.DryExtensionService.Offer;
import com.laundry.machine.dto.LoadStatusResponse.PayRequest;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.service.PaymentService;
import com.laundry.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/** Extra drying time bought on the customer's running dryer (V66), priced by the dryer's price profile. */
@RestController
@RequestMapping("/api/machines/{machineCode}/extension")
@RequiredArgsConstructor
@Tag(name = "Dry extension", description = "Buy extra drying time while the dryer runs")
public class DryExtensionController {

    private final DryExtensionService extensions;
    private final PaymentService payments;

    @GetMapping
    @Operation(summary = "What extra drying time the customer can buy now, and their unpaid / last purchase")
    public Offer offer(@PathVariable String machineCode, CurrentUser customer) {
        return extensions.offer(code(machineCode), customer.id());
    }

    @PostMapping("/pay")
    @Transactional
    @Operation(summary = "Buy the offered extra time (wallet first, then payOS); the dryer gets it once paid")
    public PaymentResponse pay(@PathVariable String machineCode, @RequestBody(required = false) PayRequest request,
                               CurrentUser customer) {
        boolean useWallet = request == null || !Boolean.FALSE.equals(request.useWallet());
        Extension extension = extensions.prepare(code(machineCode), customer.id());
        return payments.createDryExtensionPayment(extension.transactionId(), customer.id(), useWallet);
    }

    private static String code(String machineCode) {
        return machineCode.trim().toUpperCase(Locale.ROOT);
    }
}
