package com.laundry.transaction.dto.response;

import com.laundry.transaction.entity.ServiceType;
import com.laundry.transaction.entity.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Read model of a transaction for the payment module. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    private UUID transactionId;
    private UUID customerId;
    private ServiceType serviceType;
    private TransactionStatus status;
    private BigDecimal totalAmount;
    private OffsetDateTime expiresAt;
    private OffsetDateTime paidAt;
    private OffsetDateTime createdAt;
}
