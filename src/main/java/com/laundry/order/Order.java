package com.laundry.order;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Một lần khách mua dịch vụ (§5). channel APP = đặt trước (có tài khoản, trả ≥ tiền giữ chỗ);
 * KIOSK = đặt ngay (trả 100%). T = scheduledStartAt.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
public class Order extends AssignedIdEntity<UUID> {

    public enum Channel { APP, KIOSK }

    public enum Service { WASH, DRY, WASH_DRY, LAUNDRY }

    public enum Status { HOLD, CONFIRMED, IN_PROGRESS, COMPLETED, CANCELLED, NO_SHOW, EXPIRED }

    public enum CancellationKind { FREE, LATE, NO_SHOW, STORE_FAULT, UNPAID }

    @Id
    private UUID id = UUID.randomUUID();
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 6)
    private String code;
    private String qrTokenHash;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    @Enumerated(EnumType.STRING)
    private Service service;
    private UUID customerId;
    private String guestPhone;
    private UUID kioskId;
    @Enumerated(EnumType.STRING)
    private Status status;
    private Instant scheduledStartAt;
    private Instant holdExpiresAt;
    private Instant checkinOpensAt;
    private Instant checkinDeadlineAt;
    @JdbcTypeCode(SqlTypes.JSON)
    private String policySnapshot;
    private long totalAmount;
    private long depositAmount;
    private long paidAmount;
    private long refundedAmount;
    private long forfeitedAmount;
    private int rescheduleCount;
    private Instant checkedInAt;
    private Instant completedAt;
    private Instant cancelledAt;
    @Enumerated(EnumType.STRING)
    private CancellationKind cancellationKind;
    private UUID cancelledBy;
    private String cancelReason;
    @Column(insertable = false, updatable = false)
    private Boolean countsAsViolation;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;

    public boolean isOpen() {
        return status == Status.HOLD || status == Status.CONFIRMED || status == Status.IN_PROGRESS;
    }

    public long remainingAmount() {
        return Math.max(0, totalAmount - paidAmount);
    }

    public boolean fullyPaid() {
        return paidAmount >= totalAmount;
    }
}
