package com.laundry.transaction.controller;

import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.dto.response.TransactionResponse;
import com.laundry.transaction.platform.Platform;
import com.laundry.transaction.platform.PlatformContext;
import com.laundry.transaction.platform.PlatformPurchaseService;
import com.laundry.transaction.platform.PlatformServiceFactory;
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
 * Flow: Staff -> create purchase -> ONLINE payment via gateway
 * 
 * Refactored to use PlatformPurchaseService for platform-specific logic
 * Note: CASH payment flow has been removed - Staff must use ONLINE payment
 */
@RestController
@RequestMapping("/api/staff")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Staff Counter Purchase", description = "Staff counter purchase API")
@SecurityRequirement(name = "bearerAuth")
public class StaffPurchaseController {

    private final PlatformServiceFactory platformFactory;
    private final PurchaseService purchaseService;

    /**
     * Create a new purchase at staff counter
     */
    @PostMapping("/purchases")
    @PreAuthorize("hasAnyAuthority('ROLE_STAFF', 'ROLE_ADMIN', 'ROLE_OWNER')")
    @Operation(summary = "Create staff purchase", description = "Create a new purchase at staff counter")
    public ResponseEntity<TransactionResponse> createPurchase(
            @Valid @RequestBody CreatePurchaseRequest request,
            @AuthenticationPrincipal UserDetails user) {
        
        PlatformContext.setPlatform(Platform.STAFF);
        
        try {
            log.info("Staff purchase request: staff={}, serviceType={}", 
                     user.getUsername(), request.getServiceType());
            
            PlatformPurchaseService platformService = platformFactory.getService(Platform.STAFF);
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
    @PreAuthorize("hasAnyAuthority('ROLE_STAFF', 'ROLE_ADMIN', 'ROLE_OWNER')")
    @Operation(summary = "Get purchase", description = "Get purchase by ID")
    public ResponseEntity<TransactionResponse> getPurchase(@PathVariable UUID id) {
        log.info("Get purchase: id={}", id);
        
        TransactionResponse response = purchaseService.getTransaction(id);
        return ResponseEntity.ok(response);
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
