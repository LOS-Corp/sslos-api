package com.laundry.transaction.service;

import com.laundry.transaction.entity.*;
import com.laundry.transaction.exception.ResourceNotAvailableException;
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
     * Create a temporary drop-off locker reservation (PENDING_PAYMENT)
     */
    @Transactional
    public DropOffLockerReservation createTemporaryReservation(LaundryServiceOrder order, UUID lockerId) {
        // TODO: Add locker availability check when locker management is implemented
        
        DropOffLockerReservation reservation = DropOffLockerReservation.builder()
            .lockerId(lockerId)
            .status(LockerReservationStatus.RESERVED)
            .build();
        
        reservation.setLaundryOrder(order);
        
        log.info("Creating temporary drop-off locker reservation: lockerId={}, orderId={}", 
                 lockerId, order.getId());
        
        return reservationRepository.save(reservation);
    }

    /**
     * Confirm reservation after payment
     */
    @Transactional
    public void confirmReservation(UUID reservationId) {
        int updated = reservationRepository.updateStatus(reservationId, LockerReservationStatus.CONFIRMED, LocalDateTime.now());
        if (updated == 0) {
            throw new ResourceNotAvailableException("Locker Reservation", reservationId.toString());
        }
        log.info("Drop-off locker reservation confirmed: {}", reservationId);
    }

    /**
     * Cancel reservation and release locker
     */
    @Transactional
    public void cancelReservation(UUID reservationId) {
        int updated = reservationRepository.updateStatus(reservationId, LockerReservationStatus.CANCELLED, LocalDateTime.now());
        if (updated > 0) {
            log.info("Drop-off locker reservation cancelled: {}", reservationId);
        }
    }

    /**
     * Cancel reservation by order ID
     */
    @Transactional
    public void cancelByOrderId(UUID orderId) {
        int cancelled = reservationRepository.cancelByOrderId(orderId, LocalDateTime.now());
        log.info("Cancelled {} drop-off locker reservations for order: {}", cancelled, orderId);
    }

    /**
     * Get reservation by laundry order ID
     */
    public DropOffLockerReservation getByOrderId(UUID orderId) {
        return reservationRepository.findByLaundryOrderId(orderId)
            .orElseThrow(() -> new ResourceNotAvailableException("Locker Reservation not found for order", orderId.toString()));
    }

    /**
     * Mark reservation as used
     */
    @Transactional
    public void markAsUsed(UUID reservationId) {
        int updated = reservationRepository.updateStatus(reservationId, LockerReservationStatus.USED, LocalDateTime.now());
        if (updated == 0) {
            throw new ResourceNotAvailableException("Locker Reservation", reservationId.toString());
        }
        log.info("Drop-off locker reservation marked as used: {}", reservationId);
    }
}
