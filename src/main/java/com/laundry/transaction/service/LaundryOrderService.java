package com.laundry.transaction.service;

import com.laundry.transaction.entity.*;
import com.laundry.transaction.exception.ResourceNotAvailableException;
import com.laundry.transaction.repository.LaundryServiceOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Service for managing laundry service orders
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LaundryOrderService {

    private final LaundryServiceOrderRepository orderRepository;
    private final DropOffLockerReservationService dropOffLockerService;

    // Base rate per kg
    private static final BigDecimal BASE_RATE_PER_KG = new BigDecimal("15.000");

    /**
     * Calculate estimated price based on weight
     */
    public BigDecimal calculatePrice(BigDecimal weightKg) {
        if (weightKg == null || weightKg.compareTo(BigDecimal.ZERO) <= 0) {
            return BASE_RATE_PER_KG.multiply(new BigDecimal("3")); // Minimum 3kg
        }
        return BASE_RATE_PER_KG.multiply(weightKg);
    }

    /**
     * Create a laundry service order with temporary drop-off locker reservation
     */
    @Transactional
    public LaundryServiceOrder createOrder(Transaction transaction, String customerName, 
                                            String customerPhone, String serviceNotes,
                                            BigDecimal weightKg, UUID lockerId) {
        // Calculate price
        BigDecimal estimatedWeight = weightKg != null ? weightKg : new BigDecimal("3");
        BigDecimal estimatedPrice = calculatePrice(estimatedWeight);

        // Create order
        LaundryServiceOrder order = LaundryServiceOrder.builder()
            .customerName(customerName)
            .customerPhone(customerPhone)
            .serviceNotes(serviceNotes)
            .weightKg(estimatedWeight)
            .estimatedCompletion(LocalDateTime.now().plusHours(24)) // Default 24 hours
            .status(LaundryOrderStatus.PENDING)
            .build();
        
        order.setTransaction(transaction);
        
        log.info("Creating laundry service order: transactionId={}, customerName={}", 
                 transaction.getId(), customerName);
        
        order = orderRepository.save(order);
        
        // Create drop-off locker reservation if lockerId provided
        if (lockerId != null) {
            DropOffLockerReservation lockerReservation = dropOffLockerService.createTemporaryReservation(order, lockerId);
            order.setDropOffLockerReservation(lockerReservation);
        }
        
        return order;
    }

    /**
     * Confirm order (called after payment)
     */
    @Transactional
    public void confirmOrder(UUID orderId) {
        int updated = orderRepository.updateStatus(orderId, LaundryOrderStatus.RECEIVED, LocalDateTime.now());
        if (updated == 0) {
            throw new ResourceNotAvailableException("Laundry Order", orderId.toString());
        }
        log.info("Laundry service order confirmed: {}", orderId);
    }

    /**
     * Cancel order
     */
    @Transactional
    public void cancelOrder(UUID orderId) {
        LaundryServiceOrder order = getById(orderId);
        
        // Cancel drop-off locker reservation if exists
        if (order.getDropOffLockerReservation() != null) {
            dropOffLockerService.cancelByOrderId(orderId);
        }
        
        int updated = orderRepository.updateStatus(orderId, LaundryOrderStatus.CANCELLED, LocalDateTime.now());
        if (updated > 0) {
            log.info("Laundry service order cancelled: {}", orderId);
        }
    }

    /**
     * Get order by ID
     */
    public LaundryServiceOrder getById(UUID orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotAvailableException("Laundry Order", orderId.toString()));
    }

    /**
     * Get order by transaction ID
     */
    public LaundryServiceOrder getByTransactionId(UUID transactionId) {
        return orderRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new ResourceNotAvailableException("Laundry Order not found for transaction", transactionId.toString()));
    }

    /**
     * Update order status
     */
    @Transactional
    public void updateStatus(UUID orderId, LaundryOrderStatus status) {
        int updated = orderRepository.updateStatus(orderId, status, LocalDateTime.now());
        if (updated == 0) {
            throw new ResourceNotAvailableException("Laundry Order", orderId.toString());
        }
        log.info("Laundry service order status updated: orderId={}, status={}", orderId, status);
    }
}
