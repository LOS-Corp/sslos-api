package com.laundry.transaction.controller;

import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.dto.response.TransactionResponse;
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

import java.util.UUID;

/**
 * Mobile Purchase API
 * 
 * Flow: Customer -> select service -> create transaction -> payment -> QR
 * 
 * Refactored to use PlatformPurchaseService for platform-specific logic
 */
@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Mobile Purchase", description = "Mobile purchase API for customers")
public class MobilePurchaseController {

    private final PlatformServiceFactory platformFactory;
    private final PurchaseService purchaseService;

    /**
     * Create a new purchase transaction
     */
    @PostMapping("/purchases")
    @Operation(summary = "Create purchase", description = "Create a new purchase transaction")
    public ResponseEntity<TransactionResponse> createPurchase(
            @Valid @RequestBody CreatePurchaseRequest request) {
        
        PlatformContext.setPlatform(Platform.MOBILE);
        
        try {
            log.info("Mobile purchase request: customerId={}, serviceType={}", 
                     request.getCustomerId(), request.getServiceType());
            
            PlatformPurchaseService platformService = platformFactory.getService(Platform.MOBILE);
            TransactionResponse response = platformService.createPurchase(request);
            
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } finally {
            PlatformContext.clear();
        }
    }

    /**
     * Get purchase by ID
     */
    @GetMapping("/purchases/{id}")
    @Operation(summary = "Get purchase", description = "Get purchase transaction by ID")
    public ResponseEntity<TransactionResponse> getPurchase(@PathVariable UUID id) {
        log.info("Get purchase request: id={}", id);
        
        TransactionResponse response = purchaseService.getTransaction(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Cancel a pending purchase
     */
    @DeleteMapping("/purchases/{id}")
    @Operation(summary = "Cancel purchase", description = "Cancel a pending purchase transaction")
    public ResponseEntity<Void> cancelPurchase(@PathVariable UUID id) {
        log.info("Cancel purchase request: id={}", id);
        
        purchaseService.cancelTransaction(id);
        
        return ResponseEntity.noContent().build();
    }
}
