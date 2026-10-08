package com.laundry.transaction.platform;

import com.laundry.payment.dto.response.PaymentResponse;
import com.laundry.payment.entity.PaymentMethod;
import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.dto.response.TransactionResponse;
import com.laundry.transaction.entity.ServiceType;
import com.laundry.transaction.exception.TransactionException;
import com.laundry.transaction.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Staff Counter Platform Purchase Service
 * 
 * Business Rules:
 * - Staff authenticated users only
 * - Both SELF_SERVICE and LAUNDRY_SERVICE supported
 * - ONLINE payment only (via payment gateway)
 * - Staff can override prices (future)
 * - Staff can create purchases for walk-in customers
 * 
 * SRS Reference: BR-STAFF-001, BR-STAFF-002, BR-STAFF-003
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaffPlatformService implements PlatformPurchaseService {

    private final PurchaseService purchaseService;

    @Override
    public Platform getPlatform() {
        return Platform.STAFF;
    }

    @Override
    public void validateRequest(CreatePurchaseRequest request) {
        log.info("Validating Staff purchase request: serviceType={}", request.getServiceType());

        // Staff can create purchases for customers (with customer ID) or walk-ins (without)
        // For walk-ins at counter, customer ID can be null

        // Validate service-specific requirements
        if (request.getServiceType() == ServiceType.SELF_SERVICE) {
            if (request.getMachineId() == null) {
                throw new TransactionException("Machine ID is required for SELF_SERVICE purchase");
            }
        }

        if (request.getServiceType() == ServiceType.LAUNDRY_SERVICE) {
            if (request.getCustomerName() == null || request.getCustomerName().isBlank()) {
                throw new TransactionException("Customer name is required for LAUNDRY_SERVICE");
            }
        }

        log.info("Staff purchase validation passed");
    }

    @Override
    public TransactionResponse createPurchase(CreatePurchaseRequest request) {
        validateRequest(request);
        
        // Staff can create for anonymous customers
        if (request.getCustomerId() == null) {
            log.info("Staff creating purchase for walk-in customer");
        }
        
        return purchaseService.createPurchase(request);
    }

    @Override
    public PaymentMethod getDefaultPaymentMethod() {
        // Staff counter uses ONLINE payment via gateway
        return PaymentMethod.ONLINE;
    }
}
