package com.laundry.transaction.service;

import com.laundry.shared.scheduling.JobLock;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Transaction (order envelope) lifecycle used by the payment flow: read, confirm after payment,
 * cancel, and expire unpaid transactions. Purchases are created only through bookings
 * ({@code /api/bookings/holds}); the old Mobile/Kiosk/Staff purchase endpoints were removed (SC-27).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PurchaseService {

    private final TransactionRepository transactionRepository;
    private final JobLock jobLock;
    private final MachineReservationService machineReservationService;
    private final LaundryOrderService laundryOrderService;

    @Value("${app.transaction.expiration-minutes:15}")
    private int transactionExpirationMinutes;

    @Value("${app.transaction.expiration-batch:100}")
    private int transactionExpirationBatch;

    /**
     * Get transaction by ID
     */
    @Transactional(readOnly = true)
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
     * Scheduled job to expire old pending transactions and release reserved resources.
     *
     * <p>Scheduling: every {@code app.transaction.expiration-interval-ms} (default 60s).
     * Window: {@code app.transaction.expiration-window-minutes} (default 0 — only
     * strictly past the deadline). Batch: {@code app.transaction.expiration-batch} (default 100).
     *
     * <p>Concurrency model — payment vs. expiration:
     * <ul>
     *   <li>Selection returns IDs only (no entity load).</li>
     *   <li>A single conditional bulk UPDATE on transactions flips
     *       PENDING_PAYMENT → EXPIRED only for rows still in PENDING_PAYMENT. A
     *       payment callback arriving in the race window causes that row to be
     *       skipped (no risk of clobbering a freshly-Paid transaction).</li>
     *   <li>Resource release (machine reservations, locker reservations, laundry
     *       orders) uses single UPDATE statements bounded by the same transaction
     *       IDs. No N+1 selects.</li>
     * </ul>
     *
     * <p>Idempotency: if the scheduler fires twice on the same expired transaction,
     * the second pass selects no rows (already EXPIRED) and no events fire.
     */
    @Scheduled(fixedDelayString = "${app.transaction.expiration-interval-ms:60000}")
    @Transactional
    public void expireOldTransactions() {
        if (!jobLock.tryLockForTransaction("transaction-expiry")) {
            return;
        }
        long startNs = System.nanoTime();
        LocalDateTime now = LocalDateTime.now();

        // 1. SELECT IDs only — projection avoids loading the full Transaction row
        //    (which would also pull in MachineReservation/LaundryServiceOrder due to
        //    the @OneToMany mappings).
        List<UUID> expiredIds = transactionRepository.findExpiredTransactionIds(
                TransactionStatus.PENDING_PAYMENT, now, transactionExpirationBatch);
        if (expiredIds.isEmpty()) {
            return;  // silent when no work — was the source of INFO spam on startup
        }

        // 2. Bulk conditional UPDATE — PENDING_PAYMENT → EXPIRED only.
        //    A payment callback that fires between SELECT and UPDATE causes the row
        //    to be skipped (status no longer matches). Rowcount is authoritative.
        int expired = transactionRepository.updateStatusBulk(
                expiredIds, TransactionStatus.PENDING_PAYMENT, TransactionStatus.EXPIRED, now);
        if (expired == 0) {
            log.debug("Transaction expiration: race lost for {} candidate(s)", expiredIds.size());
            return;
        }

        // 3. Resource release — single bulk UPDATEs bounded by the same IDs.
        //    Note: we pass the IDs we just flipped; we do NOT re-load each transaction
        //    to decide service type. The release happens for ALL expired transactions,
        //    which is correct: PENDING_PAYMENT can only belong to either path, and
        //    resource release is keyed by transaction_id, not by service_type.
        int releasedMachineReservations = machineReservationService
                .expireReservationsByTransactionBulk(expiredIds, now);
        int cancelledLaundryOrders = laundryOrderService
                .cancelOrdersByTransactionBulk(expiredIds, now);
        // (Laundry-service path is handled inside expireLazarOrdersByTransactionBulk.)

        long durationMs = (System.nanoTime() - startNs) / 1_000_000L;
        log.info("Transaction expiration: expired_transactions={}, released_machine_reservations={}, " +
                "cancelled_laundry_orders={}, duration_ms={}",
                expired, releasedMachineReservations, cancelledLaundryOrders, durationMs);
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
