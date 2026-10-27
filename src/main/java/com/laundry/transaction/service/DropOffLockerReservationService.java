package com.laundry.transaction.service;

import com.laundry.transaction.entity.*;
import com.laundry.transaction.repository.DropOffLockerReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Service for managing drop-off locker reservations
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DropOffLockerReservationService {

    private final DropOffLockerReservationRepository reservationRepository;

    
    
    
    /**
     * Cancel reservation by order ID
     */
    @Transactional
    public void cancelByOrderId(UUID orderId) {
        int cancelled = reservationRepository.cancelByOrderId(orderId, LocalDateTime.now());
        log.info("Cancelled {} drop-off locker reservations for order: {}", cancelled, orderId);
    }

    
    }
