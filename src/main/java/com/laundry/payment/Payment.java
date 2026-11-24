package com.laundry.payment;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Tiền thực nhận; mỗi dòng là một biên nhận (§14). CAPTURED: ghi nhận cho đơn.
 * REJECTED: tiền về nhưng không ghi nhận (muộn / sai số / phiếu đã đóng) -> tự hoàn (§15).
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
public class Payment extends AssignedIdEntity<UUID> {

    public enum Method { WALLET, PAYOS, SEPAY }

    public enum Status { CAPTURED, REJECTED }

    public enum RejectReason { LATE, AMOUNT_MISMATCH, INTENT_CLOSED, UNKNOWN_ORDER }

    @Id
    private UUID id = UUID.randomUUID();
    private String receiptNo;
    private UUID orderId;
    private UUID paymentIntentId;
    @Enumerated(EnumType.STRING)
    private Method method;
    private long amount;
    @Enumerated(EnumType.STRING)
    private Status status;
    @Enumerated(EnumType.STRING)
    private RejectReason rejectReason;
    private String providerReference;
    private String payerBankBin;
    private String payerAccountNumber;
    private String payerAccountName;
    private Long webhookEventId;
    private Instant receivedAt;
    @CreationTimestamp
    private Instant createdAt;
}
