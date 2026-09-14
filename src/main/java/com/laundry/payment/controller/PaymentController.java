package com.laundry.payment.controller;

import com.laundry.payment.dto.request.CreatePaymentRequest;
import com.laundry.payment.dto.request.PaymentCallbackRequest;
import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Payment API
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Payment", description = "Payment processing API")
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Create a new payment
     */
    @PostMapping
    @Operation(summary = "Create payment", description = "Create a new payment request")
    public ResponseEntity<PaymentResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request) {
        log.info("Create payment request: transactionId={}, amount={}, method={}", 
                 request.getTransactionId(), request.getAmount(), request.getPaymentMethod());
        
        PaymentResponse response = paymentService.createPayment(request);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get payment by ID
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get payment", description = "Get payment by ID")
    public ResponseEntity<PaymentResponse> getPayment(@PathVariable UUID id) {
        log.info("Get payment request: id={}", id);
        
        PaymentResponse response = paymentService.getPayment(id);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Payment callback from gateway
     */
    @PostMapping("/callback/{paymentId}")
    @Operation(summary = "Payment callback", description = "Handle payment gateway callback")
    public ResponseEntity<PaymentResponse> paymentCallback(
            @PathVariable UUID paymentId,
            @RequestBody PaymentCallbackRequest request) {
        log.info("Payment callback: paymentId={}, status={}", paymentId, request.getStatus());
        
        PaymentResponse response = paymentService.processCallback(paymentId, request);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Cancel a pending payment
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel payment", description = "Cancel a pending payment")
    public ResponseEntity<Void> cancelPayment(@PathVariable UUID id) {
        log.info("Cancel payment request: id={}", id);
        
        paymentService.cancelPayment(id);
        
        return ResponseEntity.noContent().build();
    }
}
