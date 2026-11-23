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

/** Profile giá thuê: giá một lượt và số phút một lượt dùng chung cho các máy cùng loại gắn vào nó. */
@Entity
@Table(name = "price_profiles")
@Getter
@Setter
public class PriceProfile extends AssignedIdEntity<UUID> {

    @Id
    private UUID id = UUID.randomUUID();
    private String name;
    private String description;
    @Enumerated(EnumType.STRING)
    private Machine.Type machineType;
    private long priceVnd;
    private int cycleMinutes;
    @CreationTimestamp
    private Instant createdAt;
    @UpdateTimestamp
    private Instant updatedAt;
    @Version
    private long version;
}
