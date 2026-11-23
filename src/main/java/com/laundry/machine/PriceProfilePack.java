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

/** Add-on bán thêm thời gian của profile máy sấy; máy gắn profile có gói sấy thêm (dryer_time_packs) khớp theo số phút. */
@Entity
@Table(name = "price_profile_packs")
@Getter
@Setter
public class PriceProfilePack extends AssignedIdEntity<UUID> {
    @Id
    private UUID id = UUID.randomUUID();
    private UUID profileId;
    private int minutes;
    private long priceVnd;
    private boolean active = true;
    private int displayOrder;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
}
