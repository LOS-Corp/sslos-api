package com.laundry.transaction.controller;

import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.PaymentMethod;
import com.laundry.payment.service.PaymentService;
import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.dto.response.TransactionResponse;
import com.laundry.transaction.service.PurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Staff Counter Purchase API
 * 
 * Flow: Staff -> create purchase -> Customer pays CASH -> Staff confirms -> QR
 */
@RestController
@RequestMapping("/api/staff")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Staff Counter Purchase", description = "Staff counter purchase API")
@SecurityRequirement(name = "bearerAuth")
public class StaffPurchaseController {

    private final PurchaseService purchaseService;
    private final PaymentService paymentService;

    /**
     * Create a new purchase at staff counter
     */
    @PostMapping("/purchases")
    @PreAuthorize("hasAnyAuthority('ROLE_STAFF', 'ROLE_ADMIN', 'ROLE_OWNER')")
    @Operation(summary = "Create staff purchase", description = "Create a new purchase at staff counter")
    public ResponseEntity<TransactionResponse> createPurchase(
            @Valid @RequestBody CreatePurchaseRequest request,
            @AuthenticationPrincipal UserDetails user) {
        log.info("Staff purchase request: staff={}, serviceType={}", user.getUsername(), request.getServiceType());
        
        TransactionResponse response = purchaseService.createPurchase(request);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get purchase by ID
     */
    @GetMapping("/purchases/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_STAFF', 'ROLE_ADMIN', 'ROLE_OWNER')")
    @Operation(summary = "Get purchase", description = "Get purchase by ID")
    public ResponseEntity<TransactionResponse> getPurchase(@PathVariable UUID id) {
        log.info("Get purchase: id={}", id);
        
        TransactionResponse response = purchaseService.getTransaction(id);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Create and confirm CASH payment
     * Called by Staff after customer pays cash
     */
    @PostMapping("/purchases/{id}/cash-payment")
    @PreAuthorize("hasAnyAuthority('ROLE_STAFF', 'ROLE_ADMIN', 'ROLE_OWNER')")
    @Operation(summary = "Confirm cash payment", description = "Staff confirms cash payment")
    public ResponseEntity<PaymentResponse> confirmCashPayment(
            @PathVariable UUID id,
            @RequestParam BigDecimal amount,
            @AuthenticationPrincipal UserDetails user) {
        log.info("Cash payment confirmation: staff={}, transactionId={}, amount={}", 
                 user.getUsername(), id, amount);
        
        // Create payment with CASH method
        var paymentRequest = new com.laundry.payment.dto.request.CreatePaymentRequest();
        paymentRequest.setTransactionId(id);
        paymentRequest.setAmount(amount);
        paymentRequest.setPaymentMethod(PaymentMethod.CASH);
        paymentRequest.setIdempotencyKey("staff-" + id + "-" + user.getUsername() + "-" + System.currentTimeMillis());
        
        PaymentResponse payment = paymentService.createPayment(paymentRequest);
        
        // Confirm cash payment immediately
        PaymentResponse confirmedPayment = paymentService.confirmCashPayment(payment.getPaymentId());
        
        return ResponseEntity.ok(confirmedPayment);
    }

    /**
     * Cancel a pending purchase
     */
    @DeleteMapping("/purchases/{id}")
    @PreAuthorize("hasAnyAuthority('ROLE_STAFF', 'ROLE_ADMIN', 'ROLE_OWNER')")
    @Operation(summary = "Cancel purchase", description = "Cancel a pending purchase")
    public ResponseEntity<Void> cancelPurchase(@PathVariable UUID id) {
        log.info("Cancel purchase: id={}", id);
        
        purchaseService.cancelTransaction(id);
        
        return ResponseEntity.noContent().build();
    }
}
