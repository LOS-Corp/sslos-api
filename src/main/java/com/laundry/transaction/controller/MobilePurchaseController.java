package com.laundry.transaction.controller;

import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.dto.response.TransactionResponse;
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
 */
@RestController
@RequestMapping("/api/mobile")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Mobile Purchase", description = "Mobile purchase API for customers")
public class MobilePurchaseController {

    private final PurchaseService purchaseService;

    /**
     * Create a new purchase transaction
     */
    @PostMapping("/purchases")
    @Operation(summary = "Create purchase", description = "Create a new purchase transaction")
    public ResponseEntity<TransactionResponse> createPurchase(
            @Valid @RequestBody CreatePurchaseRequest request) {
        log.info("Mobile purchase request: customerId={}, serviceType={}", 
                 request.getCustomerId(), request.getServiceType());
        
        TransactionResponse response = purchaseService.createPurchase(request);
        
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
