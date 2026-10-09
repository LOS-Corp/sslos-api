package com.laundry.payment.controller;

import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "mock")
public class LocalPaymentSimulationController {
    private final PaymentService paymentService;

    @PostMapping("/{paymentId}/simulate-success")
    public PaymentResponse simulateSuccess(@PathVariable UUID paymentId, Authentication authentication) {
        return paymentService.simulatePaymentSuccessForCustomer(paymentId, customerId(authentication));
    }

    private UUID customerId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null
                || authentication.getAuthorities().stream()
                .noneMatch(authority -> "ROLE_CUSTOMER".equals(authority.getAuthority()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Customer account is required");
        }
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid customer identity");
        }
    }
}
