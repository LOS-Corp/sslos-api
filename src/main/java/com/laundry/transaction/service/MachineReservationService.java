package com.laundry.transaction.service;

import com.laundry.transaction.entity.*;
import com.laundry.transaction.exception.DoubleBookingException;
import com.laundry.transaction.exception.ResourceNotAvailableException;
import com.laundry.transaction.repository.MachineReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    /**
     * Check if machine is available for booking
     */
    public boolean isMachineAvailable(UUID machineId, LocalDateTime startTime, LocalDateTime endTime) {
        if (endTime == null) {
            endTime = startTime.plusHours(2); // Default 2 hours
        }
        
        List<MachineReservation> overlapping = reservationRepository.findOverlappingReservations(
            machineId, startTime, endTime
        );
        
        return overlapping.isEmpty();
    }

    /**
     * Check and throw exception if machine is not available
     */
    public void checkMachineAvailability(UUID machineId, LocalDateTime startTime, LocalDateTime endTime) {
        if (!isMachineAvailable(machineId, startTime, endTime)) {
            throw new DoubleBookingException("Machine", machineId.toString());
        }
    }

    /**
     * Create a temporary machine reservation (PENDING_PAYMENT)
     */
    @Transactional
    public MachineReservation createTemporaryReservation(Transaction transaction, UUID machineId, LocalDateTime startTime, 
                                                       LocalDateTime expiresAt) {
        // Double check availability before creating
        checkMachineAvailability(machineId, startTime, startTime.plusHours(2));
        
        MachineReservation reservation = MachineReservation.builder()
            .transaction(transaction)
            .machineId(machineId)
            .startTime(startTime)
            .endTime(startTime.plusHours(2))
            .status(ReservationStatus.RESERVED)
            .build();
        
        log.info("Creating temporary machine reservation: machineId={}, transactionId={}", 
                 machineId, transaction != null ? transaction.getId() : null);
        
        return reservationRepository.save(reservation);
    }

    @Transactional
    public MachineReservation createTemporaryReservation(UUID machineId, LocalDateTime startTime, 
                                                       LocalDateTime expiresAt, Transaction transaction) {
        return createTemporaryReservation(transaction, machineId, startTime, expiresAt);
    }

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
        }
    }

    /**
     * Cancel all reservations for a transaction
     */
    @Transactional
    public void cancelReservationsByTransaction(UUID transactionId) {
        int cancelled = reservationRepository.cancelByTransactionId(transactionId, LocalDateTime.now());
        log.info("Cancelled {} machine reservations for transaction: {}", cancelled, transactionId);
    }

    /**
     * Get reservation by transaction ID
     */
    public MachineReservation getByTransactionId(UUID transactionId) {
        return reservationRepository.findByTransactionId(transactionId)
            .orElseThrow(() -> new ResourceNotAvailableException("Reservation not found for transaction", transactionId.toString()));
    }

    /**
     * Update reservation status
     */
    @Transactional
    public void updateStatus(UUID reservationId, ReservationStatus status) {
        int updated = reservationRepository.updateStatus(reservationId, status, LocalDateTime.now());
        if (updated == 0) {
            throw new ResourceNotAvailableException("Reservation", reservationId.toString());
        }
    }
}
