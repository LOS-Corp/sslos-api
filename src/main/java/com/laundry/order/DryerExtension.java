package com.laundry.order;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dryer_extensions")
@Getter
@Setter
public class DryerExtension extends AssignedIdEntity<UUID> {

    public enum Status { PENDING_PAYMENT, PAID, APPLIED, FAILED, EXPIRED }

    @Id
    private UUID id = UUID.randomUUID();
    private UUID orderLegId;
    private UUID packId;
    private int minutes;
    private long priceAmount;
    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING_PAYMENT;
    private UUID paymentIntentId;
    private UUID deviceCommandId;
    private Instant appliedAt;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
}
