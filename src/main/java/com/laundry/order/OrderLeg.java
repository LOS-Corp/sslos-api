package com.laundry.order;

import com.laundry.common.AssignedIdEntity;
import com.laundry.machine.Machine;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Chặng dùng máy: WASH/DRY có 1 chặng, WASH_DRY có 2 (1 = giặt, 2 = sấy). Giá chặng = giá máy lúc đặt. */
@Entity
@Table(name = "order_legs")
@Getter
@Setter
public class OrderLeg extends AssignedIdEntity<UUID> {

    public enum Status { SCHEDULED, ACTIVATED, RUNNING, AWAITING_UNLOAD, DONE, CANCELLED, INTERRUPTED }

    @Id
    private UUID id = UUID.randomUUID();
    private UUID orderId;
    private int legNo;
    @Enumerated(EnumType.STRING)
    private Machine.Type machineType;
    private UUID machineId;
    private UUID reservationId;
    private long priceAmount;
    private int cycleMinutes;
    private BigDecimal capacityKg;
    @Enumerated(EnumType.STRING)
    private Status status = Status.SCHEDULED;
    private Instant plannedStartAt;
    /** Mốc chót được kích hoạt / bấm START: T + ân hạn (chặng 1), hoặc lúc giặt xong + chuyển đồ (chặng sấy). */
    private Instant startDeadlineAt;
    private Instant activatedAt;
    private Instant activationExpiresAt;
    private int activationCount;
    private Instant startedAt;
    private Instant expectedEndAt;
    private Instant finishedAt;
    private Instant doneAt;
    private int extraMinutes;
    private boolean stoppedByCustomer;
    private String interruptionReason;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;

    public boolean notStarted() {
        return status == Status.SCHEDULED || status == Status.ACTIVATED;
    }

    public boolean isOpen() {
        return status == Status.SCHEDULED || status == Status.ACTIVATED || status == Status.RUNNING
                || status == Status.AWAITING_UNLOAD;
    }
}
