package com.laundry.qr.controller;

import com.laundry.qr.dto.response.QrTokenResponse;
import com.laundry.qr.entity.QrTokenType;
import com.laundry.qr.service.QrTokenService;
import com.laundry.transaction.entity.TransactionStatus;
import com.laundry.transaction.service.PurchaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * QR Token API
 */
@RestController
@RequestMapping("/api/qr")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "QR Token", description = "QR Token API")
public class QrTokenController {

    private final QrTokenService qrTokenService;
    private final PurchaseService purchaseService;

    /**
     * Get QR token for a transaction
     */
    @GetMapping("/transactions/{transactionId}")
    @Operation(summary = "Get transaction QR", description = "Get QR token for a confirmed transaction")
    public ResponseEntity<QrTokenResponse> getTransactionQr(@PathVariable UUID transactionId) {
        log.info("Get QR for transaction: {}", transactionId);
        
        var transaction = purchaseService.getTransaction(transactionId);
        
        if (transaction.getStatus() != TransactionStatus.PAID) {
            return ResponseEntity.notFound().build();
        }
        
        QrTokenType tokenType;
        switch (transaction.getServiceType()) {
            case SELF_SERVICE -> tokenType = QrTokenType.MACHINE_ACTIVATION;
            case LAUNDRY_SERVICE -> tokenType = QrTokenType.LOCKER_DROP_OFF;
            default -> throw new IllegalStateException("Unknown service type");
        }
        
        // Get reference ID based on service type
        UUID referenceId;
        if (transaction.getMachineReservation() != null) {
            referenceId = transaction.getMachineReservation().getReservationId();
        } else if (transaction.getLaundryOrder() != null) {
            referenceId = transaction.getLaundryOrder().getOrderId();
        } else {
            throw new IllegalStateException("No reservation found");
        }
        
        // Create token with 24 hour expiration
        QrTokenResponse qrToken = qrTokenService.createToken(tokenType, referenceId, "TRANSACTION", 1440);
        
        return ResponseEntity.ok(qrToken);
    }

    /**
     * Validate a QR token
     */
    @PostMapping("/validate")
    @Operation(summary = "Validate QR", description = "Validate a QR token")
    public ResponseEntity<QrTokenResponse> validateToken(@RequestParam String token) {
        log.info("Validate QR token: {}", token);
        
        QrTokenResponse response = qrTokenService.validateToken(token);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get QR token details
     */
    @GetMapping("/{token}")
    @Operation(summary = "Get QR details", description = "Get QR token details by token")
    public ResponseEntity<QrTokenResponse> getToken(@PathVariable String token) {
        log.info("Get QR token: {}", token);
        
        QrTokenResponse response = qrTokenService.getByToken(token);
        
        return ResponseEntity.ok(response);
    }
}
