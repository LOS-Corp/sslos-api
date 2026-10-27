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
import java.util.List;
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
     * Bulk cancel of laundry orders belonging to a set of expired transactions.
     * Used by the expiration scheduler — single UPDATE for the whole batch.
     * Cancels only orders that are still cancellable (PENDING/RECEIVED/PROCESSING).
     */
    @Transactional
    public int cancelOrdersByTransactionBulk(List<UUID> transactionIds, LocalDateTime now) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return 0;
        }
        return orderRepository.cancelByTransactionIds(transactionIds, now);
    }

    private LaundryServiceOrder getById(UUID orderId) {
        return orderRepository.findById(orderId)
            .orElseThrow(() -> new ResourceNotAvailableException("Laundry Order", orderId.toString()));
    }
}
