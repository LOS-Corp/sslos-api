package com.laundry.transaction.service;

import com.laundry.transaction.dto.request.CreatePurchaseRequest;
import com.laundry.transaction.dto.response.TransactionResponse;
import com.laundry.transaction.entity.*;
import com.laundry.transaction.exception.TransactionException;
import com.laundry.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Core Purchase Service - orchestrates all purchase flows
 * 
 * This service is used by:
 * - Mobile Purchase (MF-01)
 * - Kiosk Purchase (MF-02)
 * - Staff Counter Purchase (MF-03)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseService {

    private final TransactionRepository transactionRepository;
    private final MachineReservationService machineReservationService;
    private final LaundryOrderService laundryOrderService;

    @Value("${app.transaction.expiration-minutes:15}")
    private int transactionExpirationMinutes;

    /**
     * Create a new purchase transaction
     * 
     * For SELF_SERVICE: Creates transaction + machine reservation
     * For LAUNDRY_SERVICE: Creates transaction + laundry order + drop-off locker reservation
     */
    @Transactional
    public TransactionResponse createPurchase(CreatePurchaseRequest request) {
        log.info("Creating purchase: customerId={}, serviceType={}", 
                 request.getCustomerId(), request.getServiceType());

        // Validate service type specific requirements
        if (request.getServiceType() == ServiceType.SELF_SERVICE) {
            if (request.getMachineId() == null) {
                throw new TransactionException("Machine ID is required for SELF_SERVICE");
            }
        }

        if (request.getServiceType() == ServiceType.LAUNDRY_SERVICE) {
            if (request.getCustomerName() == null || request.getCustomerName().isBlank()) {
                throw new TransactionException("Customer name is required for LAUNDRY_SERVICE");
            }
        }

        // Calculate expiration time
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(transactionExpirationMinutes);

        // Calculate total amount
        BigDecimal totalAmount = calculateAmount(request);

        // Create transaction
        Transaction transaction = Transaction.builder()
            .customerId(request.getCustomerId())
            .serviceType(request.getServiceType())
            .totalAmount(totalAmount)
            .status(TransactionStatus.PENDING_PAYMENT)
            .expiresAt(expiresAt)
            .build();

        transaction = transactionRepository.save(transaction);
        log.info("Transaction created: id={}, amount={}, expiresAt={}", 
                 transaction.getId(), totalAmount, expiresAt);

        // Handle based on service type
        switch (request.getServiceType()) {
            case SELF_SERVICE -> handleSelfService(transaction, request);
            case LAUNDRY_SERVICE -> handleLaundryService(transaction, request);
        }

        return toResponse(transaction);
    }

    /**
     * Handle SELF_SERVICE purchase - creates machine reservation
     */
    private void handleSelfService(Transaction transaction, CreatePurchaseRequest request) {
        // Check machine availability
        LocalDateTime startTime = LocalDateTime.now();
        machineReservationService.checkMachineAvailability(
            request.getMachineId(), startTime, null
        );

        // Create temporary reservation
        MachineReservation reservation = machineReservationService.createTemporaryReservation(
            request.getMachineId(),
            startTime,
            transaction.getExpiresAt(),
            transaction.getId()
        );

        transaction.addMachineReservation(reservation);
        log.info("Machine reservation created: transactionId={}, machineId={}, reservationId={}",
                 transaction.getId(), request.getMachineId(), reservation.getId());
    }

    /**
     * Handle LAUNDRY_SERVICE purchase - creates laundry order + locker reservation
     */
    private void handleLaundryService(Transaction transaction, CreatePurchaseRequest request) {
        // Create laundry order with drop-off locker reservation
        LaundryServiceOrder order = laundryOrderService.createOrder(
            transaction,
            request.getCustomerName(),
            request.getCustomerPhone(),
            request.getServiceNotes(),
            request.getEstimatedWeight(),
            null // Locker ID - can be added later when locker management is ready
        );

        transaction.setLaundryServiceOrder(order);
        log.info("Laundry order created: transactionId={}, orderId={}",
                 transaction.getId(), order.getId());
    }

    /**
     * Calculate total amount based on service type
     */
    private BigDecimal calculateAmount(CreatePurchaseRequest request) {
        return switch (request.getServiceType()) {
            case SELF_SERVICE -> new BigDecimal("20.00"); // Fixed price for now
            case LAUNDRY_SERVICE -> laundryOrderService.calculatePrice(request.getEstimatedWeight());
        };
    }

    /**
     * Get transaction by ID
     */
    public TransactionResponse getTransaction(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new TransactionException("Transaction not found: " + transactionId));
        return toResponse(transaction);
    }

    /**
     * Confirm transaction after successful payment
     */
    @Transactional
    public TransactionResponse confirmPayment(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new TransactionException("Transaction not found: " + transactionId));

        if (transaction.getStatus() != TransactionStatus.PENDING_PAYMENT) {
            throw new TransactionException("Transaction is not in PENDING_PAYMENT status");
        }

        // Update transaction status
        LocalDateTime now = LocalDateTime.now();
        int updated = transactionRepository.confirmPayment(
            transactionId, TransactionStatus.PAID, now, now
        );

        if (updated == 0) {
            throw new TransactionException("Failed to confirm payment - transaction may have expired or been modified");
        }

        transaction.setStatus(TransactionStatus.PAID);
        transaction.setPaidAt(now);

        // Confirm related reservations based on service type
        switch (transaction.getServiceType()) {
            case SELF_SERVICE -> {
                MachineReservation reservation = transaction.getMachineReservations().stream()
                    .findFirst()
                    .orElseThrow(() -> new TransactionException("No machine reservation found"));
                machineReservationService.confirmReservation(reservation.getId());
            }
            case LAUNDRY_SERVICE -> {
                LaundryServiceOrder order = transaction.getLaundryServiceOrder();
                if (order != null) {
                    laundryOrderService.confirmOrder(order.getId());
                    
                    DropOffLockerReservation lockerReservation = order.getDropOffLockerReservation();
                    if (lockerReservation != null) {
                        // Note: Will be confirmed via QR token validation when customer drops off
                    }
                }
            }
        }

        log.info("Transaction payment confirmed: id={}", transactionId);
        return toResponse(transaction);
    }

    /**
     * Cancel transaction and release all resources
     */
    @Transactional
    public void cancelTransaction(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new TransactionException("Transaction not found: " + transactionId));

        if (transaction.getStatus() == TransactionStatus.CONFIRMED || 
            transaction.getStatus() == TransactionStatus.COMPLETED) {
            throw new TransactionException("Cannot cancel completed transaction");
        }

        // Release resources
        switch (transaction.getServiceType()) {
            case SELF_SERVICE -> machineReservationService.cancelReservationsByTransaction(transactionId);
            case LAUNDRY_SERVICE -> {
                LaundryServiceOrder order = transaction.getLaundryServiceOrder();
                if (order != null) {
                    laundryOrderService.cancelOrder(order.getId());
                }
            }
        }

        // Update transaction status
        transactionRepository.updateStatus(transactionId, TransactionStatus.PENDING_PAYMENT, 
                                         TransactionStatus.CANCELLED, LocalDateTime.now());

        log.info("Transaction cancelled: id={}", transactionId);
    }

    /**
     * Scheduled job to expire old pending transactions
     * Runs every minute
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void expireOldTransactions() {
        LocalDateTime now = LocalDateTime.now();
        
        // Find expired pending transactions
        var expiredTransactions = transactionRepository.findExpiredTransactions(
            TransactionStatus.PENDING_PAYMENT, now
        );

        for (Transaction transaction : expiredTransactions) {
            try {
                // Release resources
                switch (transaction.getServiceType()) {
                    case SELF_SERVICE -> machineReservationService.cancelReservationsByTransaction(transaction.getId());
                    case LAUNDRY_SERVICE -> {
                        LaundryServiceOrder order = transaction.getLaundryServiceOrder();
                        if (order != null) {
                            laundryOrderService.cancelOrder(order.getId());
                        }
                    }
                }

                // Update status to EXPIRED
                transactionRepository.updateStatus(transaction.getId(), TransactionStatus.PENDING_PAYMENT,
                        TransactionStatus.EXPIRED, now);

                log.info("Transaction expired: id={}", transaction.getId());
            } catch (Exception e) {
                log.error("Failed to expire transaction: id={}", transaction.getId(), e);
            }
        }
    }

    /**
     * Convert entity to response DTO
     */
    private TransactionResponse toResponse(Transaction transaction) {
        TransactionResponse.TransactionResponseBuilder builder = TransactionResponse.builder()
            .transactionId(transaction.getId())
            .customerId(transaction.getCustomerId())
            .serviceType(transaction.getServiceType())
            .status(transaction.getStatus())
            .totalAmount(transaction.getTotalAmount())
            .expiresAt(transaction.getExpiresAt())
            .paidAt(transaction.getPaidAt())
            .createdAt(transaction.getCreatedAt());

        // Add service-specific info
        switch (transaction.getServiceType()) {
            case SELF_SERVICE -> {
                transaction.getMachineReservations().stream().findFirst().ifPresent(res -> {
                    builder.machineReservation(TransactionResponse.MachineReservationInfo.builder()
                        .reservationId(res.getId())
                        .machineId(res.getMachineId())
                        .startTime(res.getStartTime())
                        .endTime(res.getEndTime())
                        .status(res.getStatus())
                        .build());
                });
            }
            case LAUNDRY_SERVICE -> {
                LaundryServiceOrder order = transaction.getLaundryServiceOrder();
                if (order != null) {
                    TransactionResponse.DropOffLockerInfo lockerInfo = null;
                    DropOffLockerReservation locker = order.getDropOffLockerReservation();
                    if (locker != null) {
                        lockerInfo = TransactionResponse.DropOffLockerInfo.builder()
                            .reservationId(locker.getId())
                            .lockerId(locker.getLockerId())
                            .status(locker.getStatus())
                            .build();
                    }

                    builder.laundryOrder(TransactionResponse.LaundryOrderInfo.builder()
                        .orderId(order.getId())
                        .customerName(order.getCustomerName())
                        .customerPhone(order.getCustomerPhone())
                        .weightKg(order.getWeightKg())
                        .status(order.getStatus())
                        .dropOffLocker(lockerInfo)
                        .build());
                }
            }
        }

        return builder.build();
    }
}
