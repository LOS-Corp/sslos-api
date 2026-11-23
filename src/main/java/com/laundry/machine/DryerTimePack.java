package com.laundry.machine;

import com.laundry.common.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Gói sấy thêm, giá riêng từng máy sấy (§4.1). */
@Entity
@Table(name = "dryer_time_packs")
@Getter
@Setter
public class DryerTimePack extends AssignedIdEntity<UUID> {
    @Id
    private UUID id = UUID.randomUUID();
    private UUID machineId;
    private String machineType = "DRYER";
    private int minutes;
    private long priceVnd;
    private boolean active = true;
    private int displayOrder;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
}
