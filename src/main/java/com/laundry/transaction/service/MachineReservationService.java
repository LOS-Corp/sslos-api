package com.laundry.transaction.service;

import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineStartAuthorizationService;
import com.laundry.transaction.entity.*;
import com.laundry.transaction.exception.ResourceNotAvailableException;
import com.laundry.transaction.repository.MachineReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service for managing machine reservations
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MachineReservationService {

    private final MachineReservationRepository reservationRepository;
    private final MachineRepository machineRepository;
    /**
     * Lazy injection to break the (machine ↔ transaction) service cycle —
     * both packages are siblings and Lombok's {@code @RequiredArgsConstructor}
     * generates eager final-field wiring.
     */
    @Lazy
    private final MachineStartAuthorizationService startAuthorizationService;

    
    
    
    
    /**
     * Confirm reservation after payment
     */
    @Transactional
    public void confirmReservation(UUID reservationId) {
        int updated = reservationRepository.updateStatus(reservationId, ReservationStatus.CONFIRMED, LocalDateTime.now());
        if (updated == 0) {
            throw new ResourceNotAvailableException("Reservation", reservationId.toString());
        }
        log.info("Machine reservation confirmed: {}", reservationId);
    }

    /**
     * Cancel reservation and release machine
     */
    @Transactional
    public void cancelReservation(UUID reservationId) {
        int updated = reservationRepository.updateStatus(reservationId, ReservationStatus.CANCELLED, LocalDateTime.now());
        if (updated > 0) {
            log.info("Machine reservation cancelled: {}", reservationId);
            // Revoke any active START authorization tied to this reservation.
            startAuthorizationService.revokeByReservation(reservationId, "RESERVATION_CANCELLED");
        }
    }

    /**
     * Cancel all reservations for a transaction (manual cancellation)
     */
    @Transactional
    public void cancelReservationsByTransaction(UUID transactionId) {
        int cancelled = reservationRepository.cancelByTransactionId(transactionId, LocalDateTime.now());
        log.info("Cancelled {} machine reservations for transaction: {}", cancelled, transactionId);
    }

    
    /**
     * Bulk variant of {@link #expireReservationsByTransaction} for the
     * expiration scheduler. Single UPDATE for the whole batch — bounded by
     * the transaction IDs passed by the scheduler.
     */
    @Transactional
    public int expireReservationsByTransactionBulk(List<UUID> transactionIds, LocalDateTime now) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return 0;
        }
        return reservationRepository.expireByTransactionIds(transactionIds, now);
    }

    
    }
