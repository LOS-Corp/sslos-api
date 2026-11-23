package com.laundry.wallet;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "wallet_withdrawals")
@Getter
@Setter
public class WalletWithdrawal extends AssignedIdEntity<UUID> {

    public enum Status { PENDING, COMPLETED, REJECTED, CANCELLED }

    @Id
    private UUID id = UUID.randomUUID();
    private UUID userId;
    private long amount;
    private String bankBin;
    private String bankName;
    private String bankAccountNumber;
    private String bankAccountName;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    private Instant requestedAt;
    private Instant dueAt;
    private UUID processedBy;
    private Instant processedAt;
    private String transferReference;
    private UUID proofAttachmentId;
    private String rejectReason;
    @Version
    private long version;
}
