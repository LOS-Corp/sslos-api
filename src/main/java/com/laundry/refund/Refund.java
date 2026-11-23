package com.laundry.refund;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Một khoản hoàn (§15.1). Trigger DB chặn tổng hoàn vượt số đã trả. */
@Entity
@Table(name = "refunds")
@Getter
@Setter
public class Refund extends AssignedIdEntity<UUID> {

    public enum Kind { AUTO, EXCEPTION }

    public enum Reason {
        FREE_CANCEL, LATE_CANCEL_EXCESS, NO_SHOW_EXCESS, KIOSK_CANCEL, ABANDONED_80, STORE_FAULT, MACHINE_DOWNGRADE,
        DRY_LEG_CANCELLED, EXTENSION_FAILED, LAUNDRY_NOT_DROPPED, LATE_PAYMENT, AMOUNT_MISMATCH, PAYMENT_ABANDONED,
        EXCEPTION
    }

    public enum Destination { WALLET, BANK_TRANSFER, COUNTER }

    public enum Status { PENDING_APPROVAL, APPROVED, COMPLETED, REJECTED, FAILED }

    @Id
    private UUID id = UUID.randomUUID();
    private UUID orderId;
    private UUID paymentId;
    private long amount;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @Enumerated(EnumType.STRING)
    private Reason reasonCode;
    private String reasonText;
    @Enumerated(EnumType.STRING)
    private Destination destination;
    @Enumerated(EnumType.STRING)
    private Status status;
    private UUID recipientUserId;
    private String bankBin;
    private String bankAccountNumber;
    private String bankAccountName;
    private UUID requestedBy;
    private UUID approvedBy;
    private Instant approvedAt;
    private String rejectedReason;
    private UUID completedBy;
    private Instant completedAt;
    private String transferReference;
    private UUID proofAttachmentId;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;
}
