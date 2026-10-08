package com.laundry.transaction.controller;

import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.PaymentMethod;
import com.laundry.payment.service.PaymentService;
import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.dto.response.TransactionResponse;
import com.laundry.transaction.platform.KioskPlatformService;
import com.laundry.transaction.platform.Platform;
import com.laundry.transaction.platform.PlatformContext;
import com.laundry.transaction.platform.PlatformPurchaseService;
import com.laundry.transaction.platform.PlatformServiceFactory;
import com.laundry.transaction.service.PurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Kiosk Purchase API
 * 
 * Flow: Walk-in Customer -> Kiosk -> Online Payment -> QR Token
 * 
 * Refactored to use PlatformPurchaseService for platform-specific logic
 */
@RestController
@RequestMapping("/api/kiosk")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Kiosk Purchase", description = "Kiosk purchase API for walk-in customers")
public class KioskPurchaseController {

    private final PlatformServiceFactory platformFactory;
    private final PaymentService paymentService;
    private final PurchaseService purchaseService;

    /**
     * Create a new kiosk purchase
     */
    @PostMapping("/purchases")
    @Operation(summary = "Create kiosk purchase", description = "Create a new purchase at kiosk")
    public ResponseEntity<TransactionResponse> createPurchase(
            @Valid @RequestBody CreatePurchaseRequest request) {
        
        PlatformContext.setPlatform(Platform.KIOSK);
        
        try {
            log.info("Kiosk purchase request: serviceType={}", request.getServiceType());
            
            PlatformPurchaseService platformService = platformFactory.getService(Platform.KIOSK);
            TransactionResponse response = platformService.createPurchase(request);
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } finally {
            PlatformContext.clear();
        }
    }

    /**
     * Get kiosk purchase by ID
     */
    @GetMapping("/purchases/{id}")
    @Operation(summary = "Get kiosk purchase", description = "Get purchase by ID")
    public ResponseEntity<TransactionResponse> getPurchase(@PathVariable UUID id) {
        log.info("Get kiosk purchase: id={}", id);
        
        TransactionResponse response = purchaseService.getTransaction(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Create payment for kiosk purchase (Online payment only)
     */
    @PostMapping("/purchases/{id}/payment")
    @Operation(summary = "Create payment", description = "Create online payment for kiosk purchase")
    public ResponseEntity<PaymentResponse> createPayment(
            @PathVariable UUID id,
            @RequestParam BigDecimal amount) {
        log.info("Create kiosk payment: transactionId={}, amount={}", id, amount);
        
        var paymentRequest = new com.laundry.payment.dto.request.CreatePaymentRequest();
        paymentRequest.setTransactionId(id);
        paymentRequest.setAmount(amount);
        paymentRequest.setPaymentMethod(PaymentMethod.ONLINE);
        paymentRequest.setIdempotencyKey("kiosk-" + id + "-" + System.currentTimeMillis());
        
        PaymentResponse response = paymentService.createPayment(paymentRequest);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Cancel a pending kiosk purchase
     */
    @DeleteMapping("/purchases/{id}")
    @Operation(summary = "Cancel kiosk purchase", description = "Cancel a pending purchase")
    public ResponseEntity<Void> cancelPurchase(@PathVariable UUID id) {
        log.info("Cancel kiosk purchase: id={}", id);
        
        // Kiosk-specific: May have restrictions on cancellation after payment initiated
        purchaseService.cancelTransaction(id);
        
        return ResponseEntity.noContent().build();
    }
}
