package com.laundry.kiosk;

import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.service.PaymentService;
import com.laundry.shared.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Development only (mock gateway): the kiosk can confirm its own pending payment, as the customer app does. */
@RestController
@RequestMapping("/api/kiosk/payments")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payment.gateway", havingValue = "mock")
public class KioskPaymentSimulationController {

    private final PaymentService paymentService;

    @PostMapping("/{paymentId}/simulate-success")
    public PaymentResponse simulateSuccess(@PathVariable UUID paymentId, CurrentUser device) {
        return paymentService.simulatePaymentSuccessForCustomer(paymentId, device.id());
    }
}
