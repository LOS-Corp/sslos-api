package com.laundry.machine;

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

/**
 * Một khoảng trên dòng thời gian của máy: giữ máy cho một chặng của đơn, hoặc bảo trì.
 * Các khoảng active không bao giờ chồng nhau (EXCLUDE constraint ở DB).
 */
@Entity
@Table(name = "machine_reservations")
@Getter
@Setter
public class MachineReservation extends AssignedIdEntity<UUID> {

    public enum Kind { ORDER_LEG, MAINTENANCE }

    @Id
    private UUID id = UUID.randomUUID();
    private UUID machineId;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    private Instant startsAt;
    private Instant endsAt;
    private boolean active = true;
    private String note;
    private UUID createdBy;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;
}
