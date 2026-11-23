package com.laundry.notification;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
@Getter
@Setter
public class Notification extends AssignedIdEntity<UUID> {

    public enum Type {
        ORDER_CONFIRMED, PAYMENT_RECEIVED, REMIND_BEFORE_START, REMIND_AT_START, GRACE_ENDING, MACHINE_REASSIGNED,
        CYCLE_ENDING, CYCLE_DONE, UNLOAD_OVERDUE, ORDER_CANCELLED, REFUND_ISSUED, LAUNDRY_QUOTED, LAUNDRY_READY,
        FOUND_ITEM_NOTICE, WITHDRAWAL_UPDATED
    }

    public enum Channel { IN_APP, PUSH, SMS, EMAIL }

    public enum Status { PENDING, SENT, FAILED, CANCELLED }

    @Id
    private UUID id = UUID.randomUUID();
    private UUID userId;
    private String phone;
    private UUID orderId;
    @Enumerated(EnumType.STRING)
    private Type type;
    @Enumerated(EnumType.STRING)
    private Channel channel;
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;
    private Instant scheduledAt;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;
    private int attempts;
    private Instant sentAt;
    private Instant readAt;
    private String lastError;
    private String dedupeKey;
    @CreationTimestamp
    private Instant createdAt;
}
