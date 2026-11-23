package com.laundry.wallet;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/** Sổ cái ví, chỉ thêm. amount = biến động số dư khả dụng (có dấu), heldDelta = biến động số giữ. */
@Entity
@Table(name = "wallet_transactions")
@Immutable
@Getter
@Setter
public class WalletTransaction extends AssignedIdEntity<UUID> {

    public enum Type { REFUND, COMPENSATION, PAYMENT, WITHDRAWAL_HOLD, WITHDRAWAL_RELEASE, WITHDRAWAL_PAYOUT }

    @Id
    private UUID id = UUID.randomUUID();
    private UUID userId;
    @Enumerated(EnumType.STRING)
    private Type entryType;
    private long amount;
    private long heldDelta;
    private long balanceAfter;
    private long heldAfter;
    private UUID refundId;
    private UUID paymentId;
    private UUID withdrawalId;
    private UUID claimId;
    private String idempotencyKey;
    private String note;
    private UUID createdBy;
    @CreationTimestamp
    private Instant createdAt;
}
