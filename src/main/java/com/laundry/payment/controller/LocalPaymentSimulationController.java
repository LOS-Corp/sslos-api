package com.laundry.payment.controller;

import com.laundry.shared.security.CurrentUser;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "mock")
public class LocalPaymentSimulationController {
    private final PaymentService paymentService;

    @PostMapping("/{paymentId}/simulate-success")
    public PaymentResponse simulateSuccess(@PathVariable UUID paymentId, CurrentUser customer) {
        return paymentService.simulatePaymentSuccessForCustomer(paymentId, customer.id());
    }

    }
