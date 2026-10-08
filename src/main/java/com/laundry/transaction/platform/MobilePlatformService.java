package com.laundry.transaction.platform;

import com.laundry.payment.entity.PaymentMethod;
import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.entity.ServiceType;
import com.laundry.transaction.exception.TransactionException;
import com.laundry.transaction.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Mobile Platform Purchase Service
 * 
 * Business Rules:
 * - Authenticated customers only
 * - Both SELF_SERVICE and LAUNDRY_SERVICE supported
 * - Online payment only
 * - Can cancel pending purchases
 * - Loyalty points integration (future)
 * 
 * SRS Reference: BR-MOB-001, BR-MOB-002, BR-MOB-003
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MobilePlatformService implements PlatformPurchaseService {

    private final PurchaseService purchaseService;

    @Override
    public Platform getPlatform() {
        return Platform.MOBILE;
    }

    @Override
    public void validateRequest(CreatePurchaseRequest request) {
        log.info("Validating Mobile purchase request: customerId={}, serviceType={}", 
                 request.getCustomerId(), request.getServiceType());

        // Customer ID is required
        if (request.getCustomerId() == null) {
            throw new TransactionException("Customer ID is required for Mobile purchases");
        }

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

        // TODO: Loyalty points validation (BR-MOB-003)
        // if (request.getUseLoyaltyPoints() != null && request.getUseLoyaltyPoints()) {
        //     validateLoyaltyPoints(request.getCustomerId(), request.getTotalAmount());
        // }

        log.info("Mobile purchase validation passed: customerId={}", request.getCustomerId());
    }

    @Override
    public com.laundry.transaction.dto.response.TransactionResponse createPurchase(CreatePurchaseRequest request) {
        validateRequest(request);
        return purchaseService.createPurchase(request);
    }

    @Override
    public PaymentMethod getDefaultPaymentMethod() {
        // Mobile only supports ONLINE payment
        return PaymentMethod.ONLINE;
    }
}
