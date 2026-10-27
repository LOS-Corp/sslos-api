package com.laundry.transaction.service;

import com.laundry.shared.error.BusinessException;
import com.laundry.transaction.dto.response.TransactionResponse;
import com.laundry.transaction.entity.Transaction;
import com.laundry.transaction.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Reads the payment header of a booking. Transactions are created and moved through their states by
 * {@link com.laundry.booking.service.ScheduledBookingService}; this is the payment module's read side.
 */
@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private final TransactionRepository transactions;

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(UUID transactionId) {
        Transaction transaction = transactions.findById(transactionId)
                .orElseThrow(() -> BusinessException.notFound("Transaction not found"));
        return TransactionResponse.builder()
                .transactionId(transaction.getId())
                .customerId(transaction.getCustomerId())
                .serviceType(transaction.getServiceType())
                .status(transaction.getStatus())
                .totalAmount(transaction.getTotalAmount())
                .expiresAt(transaction.getExpiresAt())
                .paidAt(transaction.getPaidAt())
                .createdAt(transaction.getCreatedAt())
                .build();
    }
}
