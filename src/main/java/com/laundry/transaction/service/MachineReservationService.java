package com.laundry.transaction.service;

import com.laundry.machine.repository.MachineRepository;
import com.laundry.machine.service.MachineStartAuthorizationService;
import com.laundry.transaction.entity.*;
import com.laundry.transaction.exception.DoubleBookingException;
import com.laundry.transaction.exception.ResourceNotAvailableException;
import com.laundry.transaction.repository.MachineReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
     * Check if machine is available for booking
     */
    public boolean isMachineAvailable(UUID machineId, LocalDateTime startTime, LocalDateTime endTime) {
        if (endTime == null) {
            endTime = startTime.plusHours(2); // Default 2 hours
        }

        machineRepository.findByIdForReservation(machineId);

        List<MachineReservation> overlapping = reservationRepository.findOverlappingReservations(
            machineId, startTime, endTime
        );

        ZoneId zone = ZoneId.systemDefault();
        OffsetDateTime scheduledStart = startTime.atZone(zone).toOffsetDateTime();
        OffsetDateTime scheduledEnd = endTime.atZone(zone).toOffsetDateTime();
        return overlapping.isEmpty()
                && reservationRepository.countScheduledConflicts(machineId, scheduledStart, scheduledEnd) == 0;
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
     * BR-PAY-002: Expire reservations due to payment timeout
     * Sets status to EXPIRED instead of CANCELLED to distinguish from manual cancellation
     */
    @Transactional
    public int expireReservationsByTransaction(UUID transactionId, LocalDateTime now) {
        int expired = reservationRepository.expireByTransactionId(transactionId, now);
        log.info("Expired {} machine reservations for transaction: {}", expired, transactionId);
        return expired;
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

    /**
     * Get reservation by transaction ID
     */
    public MachineReservation getByTransactionId(UUID transactionId) {
        return reservationRepository.findByTransactionIdOrderByCreatedAtAsc(transactionId).stream()
            .findFirst()
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
