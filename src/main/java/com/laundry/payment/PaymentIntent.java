package com.laundry.payment;

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

/** Phiếu thu: số tiền cố định, có hạn (§14). Ví trừ trước, phần thiếu qua payOS. */
@Entity
@Table(name = "payment_intents")
@Getter
@Setter
public class PaymentIntent extends AssignedIdEntity<UUID> {

    public enum Purpose { DEPOSIT, FULL, BALANCE, DRYER_EXTENSION, LAUNDRY_MINIMUM, LAUNDRY_BALANCE, STORAGE_FEE }

    public enum Status { PENDING, PAID, EXPIRED, CANCELLED, FAILED }

    /** Cổng nhận chuyển khoản của phiếu (V5); null = ví trả hết. */
    public enum Gateway { PAYOS, SEPAY }

    @Id
    private UUID id = UUID.randomUUID();
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    private Purpose purpose;
    private long amount;
    private long walletAmount;
    private long gatewayAmount;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    private Long payosOrderCode;
    @Enumerated(EnumType.STRING)
    private Gateway gateway;
    private String payosPaymentLinkId;
    private String checkoutUrl;
    private String qrCode;
    private Instant expiresAt;
    private Instant paidAt;
    private UUID createdBy;
    private UUID kioskId;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;
}
