package com.laundry.transaction.platform;

import com.laundry.payment.entity.PaymentMethod;
import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.entity.ServiceType;
import com.laundry.transaction.exception.TransactionException;
import com.laundry.transaction.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Kiosk Platform Purchase Service
 * 
 * Business Rules:
 * - Walk-in customers, no authentication required
 * - Only SELF_SERVICE purchases
 * - Online payment only
 * - No cancellation allowed after payment initiated
 * 
 * SRS Reference: BR-KSK-001, BR-KSK-002
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KioskPlatformService implements PlatformPurchaseService {

    private final PurchaseService purchaseService;

    @Override
    public Platform getPlatform() {
        return Platform.KIOSK;
    }

    @Override
    public void validateRequest(CreatePurchaseRequest request) {
        log.info("Validating Kiosk purchase request: machineId={}", request.getMachineId());

        // Kiosk only supports SELF_SERVICE
        if (request.getServiceType() != ServiceType.SELF_SERVICE) {
            throw new TransactionException("Kiosk only supports SELF_SERVICE purchases");
        }

        // Machine ID is required for SELF_SERVICE
        if (request.getMachineId() == null) {
            throw new TransactionException("Machine ID is required for SELF_SERVICE purchase at Kiosk");
        }

        // No customer ID required for Kiosk (anonymous purchase)
        log.info("Kiosk purchase validation passed: machineId={}", request.getMachineId());
    }

    @Override
    public com.laundry.transaction.dto.response.TransactionResponse createPurchase(CreatePurchaseRequest request) {
        validateRequest(request);
        
        // Kiosk purchases are anonymous (no customer ID)
        request.setCustomerId(null);
        
        return purchaseService.createPurchase(request);
    }

    @Override
    public PaymentMethod getDefaultPaymentMethod() {
        // Kiosk only supports ONLINE payment
        return PaymentMethod.ONLINE;
    }
}
